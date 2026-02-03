package dev.zenith.ppapi.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zenith.Globals;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandOutputHelper;
import dev.zenith.ppapi.api.model.ApiErrorResponse;
import dev.zenith.ppapi.api.model.PearlLoadRequest;
import dev.zenith.ppapi.api.model.PearlLoadResponse;
import dev.zenith.ppapi.api.model.PearlStatusRequest;
import dev.zenith.ppapi.api.model.PearlStatusResponse;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.zenith.ppapi.PPApiPlugin.LOG;
import static dev.zenith.ppapi.PPApiPlugin.PLUGIN_CONFIG;

public class PPApiServer {
    private static final String REQUIRED_PLUGIN_ID = "pearlplus";
    private Javalin server;
    private final Cache<String, Integer> rateLimitCache = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();

    public synchronized void start() {
        if (server != null) {
            stop();
        }
        if (!isRequiredPluginAvailable()) {
            LOG.warn("PP API not started. Required plugin '{}' is not loaded.", REQUIRED_PLUGIN_ID);
            return;
        }
        server = createServer();
        server.start(PLUGIN_CONFIG.port);
        LOG.info("PP API started on port {}", PLUGIN_CONFIG.port);
        LOG.info("Auth token: {}", PLUGIN_CONFIG.authToken);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            LOG.info("PP API stopped");
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.jettyServer().started();
    }

    private Javalin createServer() {
        return Javalin.create(config -> {
                var threadPool = new ExecutorThreadPool();
                threadPool.setDaemon(true);
                threadPool.setName("ZenithProxy-PPApi-%d");
                config.jetty.threadPool = threadPool;
                config.http.defaultContentType = "application/json";
                var objectMapper = JavalinJackson.defaultMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                config.jsonMapper(new JavalinJackson(objectMapper, false));
            })
            .beforeMatched(ctx -> {
                if (PLUGIN_CONFIG.rateLimiter) {
                    String ip = ctx.ip();
                    synchronized (this) {
                        int reqCount = rateLimitCache.get(ip, () -> 0);
                        rateLimitCache.put(ip, reqCount + 1);
                        if (reqCount >= PLUGIN_CONFIG.rateLimitRequestsPerMinute) {
                            ctx.status(429);
                            ctx.json(new ApiErrorResponse("Rate limit exceeded"));
                            ctx.skipRemainingHandlers();
                            LOG.warn("Rate limit exceeded for IP: {}", ip);
                            return;
                        }
                    }
                }
                if (!isRequiredPluginAvailable()) {
                    ctx.status(503);
                    ctx.json(new ApiErrorResponse("Required plugin is not loaded"));
                    ctx.skipRemainingHandlers();
                    LOG.warn("Denied request from {}: required plugin '{}' not loaded", ctx.ip(), REQUIRED_PLUGIN_ID);
                    return;
                }
                var authHeaderValue = ctx.header("Authorization");
                if (authHeaderValue != null) {
                    var expectedHeaderValue = PLUGIN_CONFIG.authToken;
                    if (authHeaderValue.equals(expectedHeaderValue)) {
                        // ok
                        return;
                    }
                }
                String reason = authHeaderValue == null
                    ? "Authorization header missing"
                    : "Invalid auth token";
                ctx.json(new ApiErrorResponse(reason));
                ctx.status(401);
                ctx.skipRemainingHandlers();
                LOG.warn("Denied request from {}: {}", ctx.ip(), reason);
            })
            .post("/pearlplus/status", ctx -> {
                var req = ctx.bodyAsClass(PearlStatusRequest.class);
                var playerName = req.playerName();
                if (playerName == null || playerName.isBlank()) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName is required"));
                    return;
                }
                var result = readPearlsFromConfig(playerName);
                if (result.error() != null) {
                    ctx.status(500);
                    ctx.json(new ApiErrorResponse(result.error()));
                    return;
                }
                var botInfo = readZenithBotInfo();
                ctx.json(new PearlStatusResponse(
                    result.pearls(),
                    result.output(),
                    botInfo.minecraftServer(),
                    botInfo.botUsername()
                ));
                ctx.status(200);
            })
            .post("/pearlplus/load", ctx -> {
                var req = ctx.bodyAsClass(PearlLoadRequest.class);
                var playerName = req.playerName();
                var pearlId = req.pearlId();
                if (playerName == null || playerName.isBlank() || pearlId == null || pearlId.isBlank()) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName and pearlId are required"));
                    return;
                }
                var command = "pp load " + playerName + " " + pearlId;
                var context = executeCommand(command);
                ctx.json(new PearlLoadResponse("queued", context.getMultiLineOutput()));
                ctx.status(200);
            });
    }

    private boolean isRequiredPluginAvailable() {
        return Globals.PLUGIN_MANAGER.getPlugin(REQUIRED_PLUGIN_ID) != null;
    }

    private CommandContext executeCommand(String command) {
        var context = CommandContext.create(command, PPApiCommandSource.INSTANCE);
        LOG.info("PP API executed command: {}", command);
        Globals.COMMAND.execute(context);
        context.getSource().logEmbed(context, context.getEmbed());
        CommandOutputHelper.logMultiLineOutputToTerminal(context.getMultiLineOutput());
        CommandOutputHelper.logMultiLineOutputToDiscord(context.getMultiLineOutput());
        return context;
    }

    private ConfigPearlResult readPearlsFromConfig(String playerName) {
        var plugin = Globals.PLUGIN_MANAGER.getPlugin(REQUIRED_PLUGIN_ID);
        if (plugin == null) {
            return new ConfigPearlResult(List.of(), List.of(), "PearlPlus plugin is not loaded");
        }
        try {
            var pluginClass = plugin.getClass();
            var configField = pluginClass.getDeclaredField("PLUGIN_CONFIG");
            configField.setAccessible(true);
            var config = configField.get(null);
            if (config == null) {
                return new ConfigPearlResult(List.of(), List.of(), "PearlPlus config not available");
            }
            var playersField = config.getClass().getDeclaredField("players");
            playersField.setAccessible(true);
            var playersObj = playersField.get(config);
            if (!(playersObj instanceof java.util.Map<?, ?> players)) {
                return new ConfigPearlResult(List.of(), List.of(), "PearlPlus config players map missing");
            }
            Set<String> pearls = new LinkedHashSet<>();
            for (var entry : players.values()) {
                if (entry == null) {
                    continue;
                }
                var entryClass = entry.getClass();
                var nameField = entryClass.getDeclaredField("playerName");
                nameField.setAccessible(true);
                var nameObj = nameField.get(entry);
                if (nameObj == null) {
                    continue;
                }
                var name = nameObj.toString();
                if (!name.equalsIgnoreCase(playerName)) {
                    continue;
                }
                var pearlsField = entryClass.getDeclaredField("pearls");
                pearlsField.setAccessible(true);
                var pearlsObj = pearlsField.get(entry);
                if (pearlsObj instanceof java.util.Map<?, ?> pearlMap) {
                    for (var pearlEntry : pearlMap.values()) {
                        if (pearlEntry == null) {
                            continue;
                        }
                        var pearlIdField = pearlEntry.getClass().getDeclaredField("pearlId");
                        pearlIdField.setAccessible(true);
                        var pearlIdObj = pearlIdField.get(pearlEntry);
                        if (pearlIdObj != null) {
                            pearls.add(pearlIdObj.toString());
                        }
                    }
                    if (pearlMap.isEmpty()) {
                        for (var key : pearlMap.keySet()) {
                            if (key != null) {
                                pearls.add(key.toString());
                            }
                        }
                    }
                }
            }
            List<String> output = List.of("Loaded pearls from PearlPlus config");
            return new ConfigPearlResult(new ArrayList<>(pearls), output, null);
        } catch (ReflectiveOperationException e) {
            LOG.error("Failed to read PearlPlus config", e);
            return new ConfigPearlResult(List.of(), List.of(), "Failed to read PearlPlus config");
        }
    }

    private record ConfigPearlResult(
        List<String> pearls,
        List<String> output,
        String error
    ) { }

    private BotInfo readZenithBotInfo() {
        String server = null;
        String username = null;
        var config = Globals.CONFIG;
        if (config != null) {
            if (config.authentication != null) {
                username = config.authentication.username;
            }
            if (config.client != null && config.client.server != null) {
                var address = config.client.server.address;
                if (address != null && !address.isBlank()) {
                    server = address;
                }
            }
        }
        return new BotInfo(server, username);
    }

    private record BotInfo(
        String minecraftServer,
        String botUsername
    ) { }
}
