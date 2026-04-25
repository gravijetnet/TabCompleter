package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

public class TabListener implements Listener {

    private final SpigotMain plugin;

    public TabListener(SpigotMain plugin) {
        this.plugin = plugin;
    }

    /**
     * Filters the DeclareCommands packet (1.13+): controls which command names
     * appear in the client's tab-complete list. This is the primary filter for
     * command-name tab-completion in modern Paper/Spigot.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        event.getCommands().removeIf(cmd ->
                !CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandLowest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommandHighest(PlayerCommandPreprocessEvent event) {
        cancelIfFiltered(event, true);
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

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
