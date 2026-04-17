package net.gravijet.tabcompleter.bungeecord.command;

import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.Collections;

public class TabCompleterCommand extends Command implements TabExecutor {

    private final BungeeMain plugin;

    public TabCompleterCommand(BungeeMain plugin) {
        super("tabcompleter", "tabcompleter.admin", "tc");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String prefix = BungeeMain.color(plugin.getPluginConfig().getPrefix());

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(plugin.getPluginConfig().getReloadPermission())) {
                sender.sendMessage(prefix + "\u00a7cNo permission.");
                return;
            }
            plugin.loadConfiguration();
            sender.sendMessage(prefix + "\u00a7aConfiguration reloaded.");
            return;
        }

        sender.sendMessage(prefix + "\u00a76TabCompleter v" + plugin.getDescription().getVersion() + " by gravijet.");
        sender.sendMessage(prefix + "\u00a76Usage: /tabcompleter reload");
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1
                && sender.hasPermission(plugin.getPluginConfig().getReloadPermission())
                && "reload".startsWith(args[0].toLowerCase())) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }
}
