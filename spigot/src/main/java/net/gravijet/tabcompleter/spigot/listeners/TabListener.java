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

    // Run at LOWEST (ignoreCancelled=false) to block early so subsequent plugins
    // never see the command. Run again at HIGHEST (ignoreCancelled=true) to re-block
    // in case a plugin between LOWEST and HIGHEST un-cancelled the event.
    // The no-permission message is only sent at HIGHEST to avoid a double message
    // and to skip it when another plugin cancelled first for a different reason.

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandLowest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandHighest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, true);
    }

    private void cancelIfFiltered(PlayerCommandPreprocessEvent event, boolean sendMessage) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0];
        if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission)) return;

        event.setCancelled(true);
        if (sendMessage) {
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
