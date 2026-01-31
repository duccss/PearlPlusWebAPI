package dev.zenith.ppapi;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.ppapi.api.PPApiServer;
import dev.zenith.ppapi.command.PPApiCommand;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = "pp-api",
    version = BuildConstants.VERSION,
    description = "PP API for ZenithProxy",
    url = "https://github.com/duccss/PearlPlusWebAPI",
    authors = {"duccss", "rfresh2"},
    mcVersions = {"*"}
)
public class PPApiPlugin implements ZenithProxyPlugin {
    public static PPApiConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;
    public static PPApiServer SERVER;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig("pp-api", PPApiConfig.class);
        SERVER = new PPApiServer();
        if (PLUGIN_CONFIG.enabled) {
            SERVER.start();
        }
        pluginAPI.registerCommand(new PPApiCommand());
    }
}
