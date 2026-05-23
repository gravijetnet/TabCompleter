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
import java.util.function.Predicate;

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
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // The method was found and invoked but threw — log the cause, not the wrapper.
                    plugin.getLogger().warn("[TC] Could not refresh command list for {} via {}: {}",
                            player.getUsername(), methodName,
                            e.getCause() != null ? e.getCause().toString() : e.toString());
                    return;
                } catch (Exception e) {
                    // Method exists but invocation failed (access, etc.); try next candidate.
                    plugin.getLogger().debug("[TC] Method {} failed for {}: {}", methodName, player.getUsername(), e.toString());
                }
            }
        }
        plugin.getLogger().warn("[TC] Could not refresh command list for {}: no candidate method found", player.getUsername());
    }

    private static Method findMethod(Class<?> clazz, String name) {
        // Intentionally searches for zero-argument methods only; all candidate names are zero-arg send methods.
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
            String baseCmd = afterSlash.split(" ", 2)[0];
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                tryClear(live);
            }
        } else {
            // Build a copy of what should remain visible.
            Predicate<String> visible = CommandFilter.resolve(plugin.getPluginConfig(), player::hasPermission);
            List<String> filtered = new ArrayList<>();
            for (String text : live) {
                String name = text.startsWith("/") ? text.substring(1) : text;
                if (visible.test(name)) {
                    filtered.add(text);
                }
            }
            // Replace in place. If the list is unmodifiable, log once so operators know
            // to rely on the VelocityPacketInjector safety net instead.
            try {
                live.clear();
                live.addAll(filtered);
            } catch (UnsupportedOperationException ignored) {
                plugin.getLogger().debug("[TC] TabCompleteEvent suggestion list is unmodifiable; relying on packet-level filter.");
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

        Predicate<String> visible = CommandFilter.resolve(config, player::hasPermission);

        Map<String, Object> childrenMap = getInternalMap(rootObj, "children", null);

        if (childrenMap == null) {
            // Fall back to the Brigadier API when reflection cannot reach the internal map.
            // Use instanceOfCheck for the cast (classNameCheck alone isn't sufficient for the cast).
            if (instanceOfCheck) {
                filterRootFallback((RootCommandNode) rootObj, visible, log);
            } else if (log != null) {
                log.warn("[TC] filterRoot: cannot access children map and cannot cast root — commands may leak");
            }
            return;
        }

        Set<String> toRemove = new LinkedHashSet<>();
        for (String name : childrenMap.keySet()) {
            if (!visible.test(name)) {
                toRemove.add(name);
            }
        }

        if (!toRemove.isEmpty()) {
            removeFromAllMaps(rootObj, toRemove, log);
        }
    }

    static void filterRoot(Object rootObj, Player player, PluginConfig config) {
        filterRoot(rootObj, player, config, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void filterRootFallback(RootCommandNode root, Predicate<String> visible, Logger log) {
        List<CommandNode> toKeep = new ArrayList<>();
        for (CommandNode child : new ArrayList<CommandNode>(root.getChildren())) {
            if (visible.test(child.getName())) {
                toKeep.add(child);
            }
        }

        boolean allCleared = true;
        for (String fn : new String[]{"children", "literals", "arguments"}) {
            Map<?, ?> m = getInternalMap(root, fn, log);
            if (m != null) {
                try {
                    m.clear();
                } catch (UnsupportedOperationException e) {
                    allCleared = false;
                    if (log != null) log.warn("[TC] filterRootFallback: could not clear '{}' map — blocked commands may leak", fn);
                }
            }
        }

        if (allCleared) {
            for (CommandNode child : toKeep) root.addChild(child);
        } else if (log != null) {
            log.warn("[TC] filterRootFallback: skipping re-add because not all maps were cleared — command list may be incomplete");
        }
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
                // Fall back to iterator removal; log if that also fails.
                Iterator<String> it = map.keySet().iterator();
                while (it.hasNext()) {
                    if (names.contains(it.next())) {
                        try {
                            it.remove();
                        } catch (UnsupportedOperationException ex) {
                            if (log != null) log.warn("[TC] Cannot remove commands from '{}' map — commands may leak through filter", fn);
                        }
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
