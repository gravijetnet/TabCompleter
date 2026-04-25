package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.util.ArrayList;
import java.util.List;

public class VelocityNativeListener {

    private final VelocityMain plugin;

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    /**
     * Filters the DeclareCommands packet sent to the player.
     *
     * root.getChildren() returns a live Map.values() view of Brigadier's internal
     * children map. Calling clear() on it directly empties that map — no reflection
     * needed, works on Java 17+ without any --add-opens flags. We then re-add only
     * the allowed commands via the public addChild() API.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Subscribe(order = PostOrder.LAST)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        RootCommandNode root = event.getRootNode();

        List<CommandNode> toKeep = new ArrayList<>();
        for (CommandNode child : (java.util.Collection<CommandNode>) root.getChildren()) {
            String name = child.getName().toLowerCase();
            if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), name, player::hasPermission)) {
                toKeep.add(child);
            }
        }

        // Live view — clear() empties the underlying map without reflection.
        root.getChildren().clear();

        for (CommandNode child : toKeep) {
            root.addChild(child);
        }
    }

    /**
     * Filters tab-complete suggestions for argument completions and legacy
     * command-name completions (pre-1.13 clients / non-Brigadier backends).
     */
    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            // Argument completion — suppress all suggestions if the base command is blocked.
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.getSuggestions().clear();
            }
        } else {
            // Command-name completion fallback (legacy clients / legacy backends).
            List<String> filtered = new ArrayList<>();
            for (String text : event.getSuggestions()) {
                String name = text.startsWith("/") ? text.substring(1) : text;
                if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), name.toLowerCase(), player::hasPermission)) {
                    filtered.add(text);
                }
            }
            event.getSuggestions().clear();
            event.getSuggestions().addAll(filtered);
        }
    }

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
