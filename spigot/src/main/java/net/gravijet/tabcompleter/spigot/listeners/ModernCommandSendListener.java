package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

public class ModernCommandSendListener implements Listener {

    private final SpigotMain plugin;

    public ModernCommandSendListener(SpigotMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        String perm = plugin.getPluginConfig().getBypassPermission();
        if (player.hasPermission(perm) || player.hasPermission("*")) return;

        event.getCommands().removeIf(cmd ->
                !CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission));
    }
}
