package net.gravijet.tabcompleter.bungeecord.listeners;

import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.event.TabCompleteResponseEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.function.Predicate;

public class BungeeChatListener implements Listener {

    private final BungeeMain plugin;

    public BungeeChatListener(BungeeMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(ChatEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (hasBypass(player)) return;

        String message = event.getMessage();
        if (message == null || !message.startsWith("/")) return;

        String cmd = message.substring(1).split(" ", 2)[0];
        if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission)) return;

        event.setCancelled(true);
        player.sendMessage(new TextComponent(
                BungeeMain.color(plugin.getPluginConfig().getPrefix()
                        + plugin.getPluginConfig().getNoPermissionMessage())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabComplete(TabCompleteEvent event) {
        if (event.isCancelled()) return;

        String cursor = event.getCursor();
        plugin.getLogger().info("[TC-DEBUG] TabCompleteEvent fired: cursor=" + cursor
                + " sender=" + event.getSender().getClass().getSimpleName()
                + " isProxiedPlayer=" + (event.getSender() instanceof ProxiedPlayer));

        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        plugin.getLogger().info("[TC-DEBUG] Player=" + player.getName()
                + " bypass=" + hasBypass(player)
                + " cursor=" + cursor);

        if (hasBypass(player)) return;
        if (cursor == null || !cursor.startsWith("/")) return;

        String afterSlash = cursor.substring(1);
        if (afterSlash.isEmpty()) return;

        String baseCmd = afterSlash.split(" ", 2)[0];
        boolean visible = CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission);
        plugin.getLogger().info("[TC-DEBUG] baseCmd=" + baseCmd + " visible=" + visible + " blocked-commands=" + plugin.getPluginConfig().getCommands());

        if (!visible) {
            event.setCancelled(true);
            plugin.getLogger().info("[TC-DEBUG] TabCompleteEvent CANCELLED for " + baseCmd);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabCompleteResponse(TabCompleteResponseEvent event) {
        plugin.getLogger().info("[TC-DEBUG] TabCompleteResponseEvent fired: suggestions=" + event.getSuggestions()
                + " receiver=" + event.getReceiver().getClass().getSimpleName()
                + " isProxiedPlayer=" + (event.getReceiver() instanceof ProxiedPlayer));

        if (event.isCancelled()) return;
        if (!(event.getReceiver() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getReceiver();
        if (hasBypass(player)) return;

        Predicate<String> visible = CommandFilter.resolve(plugin.getPluginConfig(), player::hasPermission);
        event.getSuggestions().removeIf(suggestion -> {
            String name = suggestion.startsWith("/") ? suggestion.substring(1) : suggestion;
            String base = name.contains(" ") ? name.split(" ", 2)[0] : name;
            boolean blocked = !visible.test(base);
            if (blocked) plugin.getLogger().info("[TC-DEBUG] Removing suggestion: " + suggestion);
            return blocked;
        });
    }

    private boolean hasBypass(ProxiedPlayer player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
