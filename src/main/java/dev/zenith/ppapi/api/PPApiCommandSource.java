package dev.zenith.ppapi.api;

import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandOutputHelper;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;

public class PPApiCommandSource implements CommandSource {
    public static final PPApiCommandSource INSTANCE = new PPApiCommandSource();

    @Override
    public String name() {
        return "PPApi";
    }

    @Override
    public boolean validateAccountOwner(final CommandContext ctx) {
        return true;
    }

    @Override
    public void logEmbed(final CommandContext commandContext, final Embed embed) {
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }
}
