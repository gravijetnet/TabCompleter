package net.gravijet.tabcompleter.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.gravijet.tabcompleter.velocity.VelocityMain;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collections;
import java.util.List;

public class TabCompleterCommand implements SimpleCommand {

    private final VelocityMain plugin;

    public TabCompleterCommand(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String prefix = plugin.getPluginConfig().getPrefix();

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!source.hasPermission(plugin.getPluginConfig().getReloadPermission())) {
                source.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(prefix + "&cNo permission."));
                return;
            }
            plugin.loadConfiguration();
            source.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(prefix + "&aConfiguration reloaded."));
            return;
        }

        if (!source.hasPermission(plugin.getPluginConfig().getReloadPermission())) {
            source.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(prefix + "&cNo permission."));
            return;
        }
        source.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(prefix + "&6TabCompleter v" + VelocityMain.PLUGIN_VERSION + " by gravijet."));
        source.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(prefix + "&6Usage: /velocitytabcompleter reload"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1
                && invocation.source().hasPermission(plugin.getPluginConfig().getReloadPermission())
                && "reload".startsWith(args[0].toLowerCase())) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(plugin.getPluginConfig().getReloadPermission())
                || invocation.source().hasPermission("*");
    }
}
