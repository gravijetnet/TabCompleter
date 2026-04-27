package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.gravijet.tabcompleter.velocity.VelocityMain;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class VelocityNativeListener {

    private final VelocityMain plugin;

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Subscribe(order = PostOrder.LAST, async = true)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        try {
            Player player = event.getPlayer();
            if (hasBypass(player)) return;

            Object rootObj = event.getRootNode();
            if (rootObj == null) return;

            filterRoot(rootObj, player, plugin.getPluginConfig(), plugin.getLogger());
        } catch (Exception e) {
            plugin.getLogger().error("[TC] Exception in onAvailableCommands: {}", e.toString());
        }
    }

    @Subscribe(order = PostOrder.LAST, async = true)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;
        String[] candidates = {"sendAvailableCommands", "sendCommandList", "sendPlayerCommands", "sendCommandTree", "sendCommands"};
        for (String methodName : candidates) {
            Method m = findMethod(player.getClass(), methodName);
            if (m != null) {
                try {
                    m.setAccessible(true);
                    m.invoke(player);
                    return;
                } catch (Exception ignored) {}
            }
        }
    }

    private static Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    // Runs last to ensure our filter is applied after other listeners have populated suggestions.
    // This fires for both modern (1.13+) and legacy (pre-1.13) clients.
    @Subscribe(order = PostOrder.LAST)
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        List<String> live = event.getSuggestions();

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                tryClear(live);
            }
        } else {
            // Build a copy of what should remain visible.
            List<String> filtered = new ArrayList<>();
            for (String text : live) {
                String name = text.startsWith("/") ? text.substring(1) : text;
                if (CommandFilter.isCommandVisibleToPlayer(
                        plugin.getPluginConfig(), name.toLowerCase(), player::hasPermission)) {
                    filtered.add(text);
                }
            }
            // Replace in place; tryClear + addAll handles unmodifiable list gracefully.
            tryClear(live);
            try {
                live.addAll(filtered);
            } catch (UnsupportedOperationException ignored) {
                // list is still unmodifiable — the packet-level interceptor in
                // VelocityPacketInjector will catch this as the safety net.
            }
        }
    }

    private static void tryClear(List<String> list) {
        try {
            list.clear();
        } catch (UnsupportedOperationException ignored) {}
    }

    boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }

    // -------------------------------------------------------------------------
    // Shared helpers — used by this listener AND VelocityPacketInjector
    // -------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void filterRoot(Object rootObj, Player player, PluginConfig config, Logger log) {
        if (rootObj == null) return;

        String actualClass = rootObj.getClass().getName();
        boolean instanceOfCheck = rootObj instanceof RootCommandNode;
        boolean classNameCheck  = actualClass.equals("com.mojang.brigadier.tree.RootCommandNode");

        if (!instanceOfCheck && !classNameCheck) {
            if (log != null) log.warn("[TC] filterRoot: unexpected root class '{}'", actualClass);
            return;
        }

        Map<String, Object> childrenMap = getInternalMap(rootObj, "children", null);

        if (childrenMap == null) {
            if (instanceOfCheck) {
                filterRootFallback((RootCommandNode) rootObj, player, config);
            }
            return;
        }

        Set<String> toRemove = new LinkedHashSet<>();
        for (String name : new ArrayList<>(childrenMap.keySet())) {
            if (!CommandFilter.isCommandVisibleToPlayer(config, name.toLowerCase(), player::hasPermission)) {
                toRemove.add(name);
            }
        }

        if (!toRemove.isEmpty()) {
            removeFromAllMaps(rootObj, toRemove, null);
        }
    }

    static void filterRoot(Object rootObj, Player player, PluginConfig config) {
        filterRoot(rootObj, player, config, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void filterRootFallback(RootCommandNode root, Player player, PluginConfig config) {
        List<CommandNode> toKeep = new ArrayList<>();
        for (CommandNode child : new ArrayList<CommandNode>(root.getChildren())) {
            if (CommandFilter.isCommandVisibleToPlayer(config, child.getName().toLowerCase(), player::hasPermission)) {
                toKeep.add(child);
            }
        }

        boolean clearedViaReflection = false;
        for (String fn : new String[]{"children", "literals", "arguments"}) {
            Map<?, ?> m = getInternalMap(root, fn, null);
            if (m != null) { m.clear(); clearedViaReflection = true; }
        }
        if (!clearedViaReflection) {
            try { root.getChildren().clear(); } catch (Exception ignored) {}
        }

        for (CommandNode child : toKeep) root.addChild(child);
    }

    @SuppressWarnings("unchecked")
    static <V> Map<String, V> getInternalMap(Object node, String fieldName, Logger log) {
        Field f = findField(node.getClass(), fieldName);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            Object val = f.get(node);
            if (val instanceof Map) return (Map<String, V>) val;
            return null;
        } catch (Exception e) {
            if (log != null) log.warn("[TC] Error accessing field '{}': {}", fieldName, e.toString());
            return null;
        }
    }

    static void removeFromAllMaps(Object node, Set<String> names, Logger log) {
        for (String fn : new String[]{"children", "literals", "arguments"}) {
            Map<String, ?> map = getInternalMap(node, fn, null);
            if (map == null) continue;
            try {
                map.keySet().removeAll(names);
            } catch (UnsupportedOperationException e) {
                Iterator<String> it = map.keySet().iterator();
                while (it.hasNext()) {
                    if (names.contains(it.next())) {
                        try { it.remove(); } catch (UnsupportedOperationException ignored) {}
                    }
                }
            }
        }
    }

    static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }
}
