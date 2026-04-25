package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class VelocityNativeListener {

    private final VelocityMain plugin;

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Subscribe(order = PostOrder.LAST)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;
        filterRoot(event.getRootNode(), player, plugin.getPluginConfig());
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.getSuggestions().clear();
            }
        } else {
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

    boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }

    // -------------------------------------------------------------------------
    // Shared static helpers — used by both this listener and VelocityPacketInjector
    // -------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void filterRoot(Object rootObj, Player player, PluginConfig config) {
        if (!(rootObj instanceof RootCommandNode)) return;
        RootCommandNode root = (RootCommandNode) rootObj;

        List<CommandNode> toKeep = new ArrayList<>();
        for (Object obj : root.getChildren()) {
            CommandNode child = (CommandNode) obj;
            if (CommandFilter.isCommandVisibleToPlayer(config, child.getName().toLowerCase(), player::hasPermission)) {
                toKeep.add(child);
            }
        }

        clearNode(root);

        for (CommandNode child : toKeep) {
            root.addChild(child);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void clearNode(RootCommandNode root) {
        // Strategy 1: public API — root.getChildren() returns Map.values() (live view).
        // Calling clear() on it clears the underlying LinkedHashMap.
        try {
            root.getChildren().clear();
        } catch (Exception ignored) {}

        // Strategy 2: reflection — clears ALL three internal Brigadier maps.
        // Needed because some Velocity versions may iterate 'literals' or 'arguments'
        // directly when serialising the DeclareCommands packet, bypassing getChildren().
        for (String fieldName : new String[]{"children", "literals", "arguments"}) {
            try {
                Field f = CommandNode.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(root);
                if (val instanceof Map) ((Map<?, ?>) val).clear();
            } catch (Exception ignored) {}
        }
    }
}
