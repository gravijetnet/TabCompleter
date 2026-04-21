package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.lang.reflect.Field;

public class TabListener implements Listener {

    private final SpigotMain plugin;
    private CommandMap commandMap;

    public TabListener(SpigotMain plugin) {
        this.plugin = plugin;
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            commandMap = (CommandMap) f.get(Bukkit.getServer());
        } catch (Exception e) {
            plugin.getLogger().warning("Could not access commandMap via reflection: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandLowest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommandHighest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, true);
        if (!event.isCancelled()) {
            cancelIfUnknown(event);
        }
    }

    private void cancelIfFiltered(PlayerCommandPreprocessEvent event, boolean sendMessage) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0].toLowerCase();
        if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission)) return;

        event.setCancelled(true);
        if (sendMessage) {
            player.sendMessage(
                    SpigotMain.color(plugin.getPluginConfig().getPrefix())
                    + SpigotMain.color(plugin.getPluginConfig().getNoPermissionMessage()));
        }
    }

    private void cancelIfUnknown(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;
        String cmd = message.substring(1).split(" ", 2)[0].toLowerCase();
        if (commandMap != null && commandMap.getCommand(cmd) == null) {
            event.setCancelled(true);
            player.sendMessage(
                    SpigotMain.color(plugin.getPluginConfig().getPrefix())
                    + SpigotMain.color(plugin.getPluginConfig().getNoPermissionMessage()));
        }
    }

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
