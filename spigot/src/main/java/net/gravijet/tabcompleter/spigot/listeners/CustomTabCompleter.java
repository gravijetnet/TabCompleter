package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class CustomTabCompleter implements TabCompleter {

    private final SpigotMain plugin;

    public CustomTabCompleter(SpigotMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if ("reload".startsWith(partial) && player.hasPermission(plugin.getPluginConfig().getReloadPermission())) {
                return Collections.singletonList("reload");
            }
        }

        return Collections.emptyList();
    }
}
