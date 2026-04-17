package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class TabListener implements Listener {

    private final SpigotMain plugin;

    public TabListener(SpigotMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandLowest(PlayerCommandPreprocessEvent event) {
        cancelIfNotAllowed(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommandHighest(PlayerCommandPreprocessEvent event) {
        cancelIfNotAllowed(event, true);
    }

    private void cancelIfNotAllowed(PlayerCommandPreprocessEvent event, boolean sendMessage) {
        Player player = event.getPlayer();
        if (player.hasPermission(plugin.getPluginConfig().getBypassPermission())) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0].toLowerCase();
        if (CommandFilter.isCommandAllowed(plugin.getPluginConfig(), player::hasPermission, cmd)) return;

        event.setCancelled(true);
        if (sendMessage) {
            player.sendMessage(
                    SpigotMain.color(plugin.getPluginConfig().getPrefix())
                    + SpigotMain.color(plugin.getPluginConfig().getNoPermissionMessage()));
        }
    }
}
