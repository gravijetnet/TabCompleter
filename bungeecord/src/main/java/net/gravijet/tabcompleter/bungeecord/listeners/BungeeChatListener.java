package net.gravijet.tabcompleter.bungeecord.listeners;

import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public class BungeeChatListener implements Listener {

    private final BungeeMain plugin;

    public BungeeChatListener(BungeeMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatLowest(ChatEvent event) {
        cancelIfNotAllowed(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatHighest(ChatEvent event) {
        cancelIfNotAllowed(event, true);
    }

    private void cancelIfNotAllowed(ChatEvent event, boolean sendMessage) {
        if (event.isCancelled()) return;
        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (player.hasPermission(plugin.getPluginConfig().getBypassPermission())) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0].toLowerCase();
        if (CommandFilter.isCommandAllowed(plugin.getPluginConfig(), player::hasPermission, cmd)) return;

        event.setCancelled(true);
        if (sendMessage) {
            player.sendMessage(
                    BungeeMain.color(plugin.getPluginConfig().getPrefix())
                    + BungeeMain.color(plugin.getPluginConfig().getNoPermissionMessage()));
        }
    }
}
