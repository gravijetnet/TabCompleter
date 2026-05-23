package net.gravijet.tabcompleter.bungeecord.listeners;

import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.List;

public class BungeeChatListener implements Listener {

    private final BungeeMain plugin;

    public BungeeChatListener(BungeeMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatLowest(ChatEvent event) {
        cancelIfBlocked(event, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatHighest(ChatEvent event) {
        if (event.isCancelled()) return;
        cancelIfBlocked(event, true);
    }

    private void cancelIfBlocked(ChatEvent event, boolean sendMessage) {
        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (hasBypass(player)) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0];

        if (!CommandFilter.isCommandBlocked(plugin.getPluginConfig(), cmd)) return;

        event.setCancelled(true);
        if (sendMessage) {
            player.sendMessage(
                    BungeeMain.color(plugin.getPluginConfig().getPrefix())
                    + BungeeMain.color(plugin.getPluginConfig().getNoPermissionMessage()));
        }
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (hasBypass(player)) return;

        String cursor = event.getCursor();
        if (cursor == null || !cursor.startsWith("/")) return;

        String afterSlash = cursor.substring(1);
        if (afterSlash.isEmpty()) return;

        if (afterSlash.contains(" ")) {
            // Argument completion — block entirely if base command is blocked.
            String baseCmd = afterSlash.split(" ", 2)[0];
            if (CommandFilter.isCommandBlocked(plugin.getPluginConfig(), baseCmd)) {
                event.setCancelled(true);
            }
        } else {
            // Command name completion — remove blocked suggestions individually.
            List<String> suggestions = event.getSuggestions();
            try {
                suggestions.removeIf(suggestion -> {
                    String name = suggestion.startsWith("/") ? suggestion.substring(1) : suggestion;
                    return CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name);
                });
            } catch (UnsupportedOperationException ignored) {
                // getSuggestions() returned an unmodifiable list; filtering skipped for this event.
            }
        }
    }

    private boolean hasBypass(ProxiedPlayer player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
