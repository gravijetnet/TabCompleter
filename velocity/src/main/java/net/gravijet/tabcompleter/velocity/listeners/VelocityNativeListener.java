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
        plugin.getLogger().info("[TC][L1] VelocityNativeListener constructed and registered.");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Subscribe(order = PostOrder.LAST, async = true)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        try {
            Player player = event.getPlayer();
            Logger log = plugin.getLogger();
            PluginConfig config = plugin.getPluginConfig();

            log.info("[TC][L1] PlayerAvailableCommandsEvent fired for player={}", player.getUsername());
            log.info("[TC][L1] Config: mode='{}', blockedCommands={}", config.getSpigotMode(), config.getBlockedCommands());

            boolean bypass = hasBypass(player);
            log.info("[TC][L1] bypass={} (bypassPerm='{}')", bypass, config.getBypassPermission());
            if (bypass) return;

            Object rootObj = event.getRootNode();
            if (rootObj == null) {
                log.warn("[TC][L1] getRootNode() returned null! Cannot filter.");
                return;
            }
            log.info("[TC][L1] Root node class: {}", rootObj.getClass().getName());

            filterRoot(rootObj, player, config, log);
        } catch (Exception e) {
            plugin.getLogger().error("[TC][L1] UNCAUGHT EXCEPTION in onAvailableCommands: {}", e.toString());
            e.printStackTrace();
        }
    }

    /**
     * Fallback: after the player connects to a backend server, force Velocity to re-send
     * the available-commands packet. This calls ConnectedPlayer#sendAvailableCommands()
     * via reflection, which fires PlayerAvailableCommandsEvent again so our filter runs.
     */
    @Subscribe(order = PostOrder.LAST, async = true)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;
        plugin.getLogger().info("[TC][L1] ServerPostConnectEvent fallback: forcing sendAvailableCommands for player={}", player.getUsername());
        try {
            Method m = findMethod(player.getClass(), "sendAvailableCommands");
            if (m != null) {
                m.setAccessible(true);
                m.invoke(player);
                plugin.getLogger().info("[TC][L1] sendAvailableCommands() invoked via reflection for player={}", player.getUsername());
            } else {
                plugin.getLogger().warn("[TC][L1] sendAvailableCommands() not found on {}", player.getClass().getName());
            }
        } catch (Exception e) {
            plugin.getLogger().warn("[TC][L1] sendAvailableCommands reflection failed for player={}: {}", player.getUsername(), e.toString());
        }
    }

    private static Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        plugin.getLogger().info("[TC][Tab] partial='{}' player={}", partial, player.getUsername());

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                plugin.getLogger().info("[TC][Tab] Clearing argument suggestions for blocked cmd '{}'", baseCmd);
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
            plugin.getLogger().info("[TC][Tab] Suggestions {} -> {}", event.getSuggestions().size(), filtered.size());
            event.getSuggestions().clear();
            event.getSuggestions().addAll(filtered);
        }
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
        if (rootObj == null) {
            if (log != null) log.warn("[TC][filter] rootObj is null");
            return;
        }

        String actualClass = rootObj.getClass().getName();

        // Check both via instanceof and class name to catch classloader-mismatch scenarios
        boolean instanceOfCheck = rootObj instanceof RootCommandNode;
        boolean classNameCheck  = actualClass.equals("com.mojang.brigadier.tree.RootCommandNode");

        if (log != null) {
            log.info("[TC][filter] root class='{}' instanceof={} classNameMatch={}",
                    actualClass, instanceOfCheck, classNameCheck);
        }

        if (!instanceOfCheck && !classNameCheck) {
            if (log != null) log.warn("[TC][filter] rootObj is not a RootCommandNode — aborting filter");
            return;
        }

        // Fetch the raw internal 'children' map directly via reflection.
        // We avoid getChildren().clear() + re-add because some Velocity builds return
        // an unmodifiable view from getChildren(), making the clear() a no-op.
        Map<String, Object> childrenMap = getInternalMap(rootObj, "children", log);

        if (childrenMap == null) {
            if (log != null) log.warn("[TC][filter] Could not access 'children' map via reflection — trying fallback");
            if (instanceOfCheck) {
                filterRootFallback((RootCommandNode) rootObj, player, config, log);
            }
            return;
        }

        if (log != null) log.info("[TC][filter] Commands before filter ({}): {}", childrenMap.size(), new ArrayList<>(childrenMap.keySet()));

        Set<String> toRemove = new LinkedHashSet<>();
        for (String name : new ArrayList<>(childrenMap.keySet())) {
            boolean visible = CommandFilter.isCommandVisibleToPlayer(config, name.toLowerCase(), player::hasPermission);
            if (log != null) log.info("[TC][filter] '{}' -> visible={}", name, visible);
            if (!visible) toRemove.add(name);
        }

        if (log != null) log.info("[TC][filter] Will remove {} commands: {}", toRemove.size(), toRemove);

        if (toRemove.isEmpty()) {
            if (log != null) log.info("[TC][filter] Nothing to remove — all commands are allowed.");
            return;
        }

        removeFromAllMaps(rootObj, toRemove, log);

        if (log != null) {
            Map<String, Object> after = getInternalMap(rootObj, "children", null);
            log.info("[TC][filter] Commands after filter ({}): {}",
                    after == null ? "?" : after.size(),
                    after == null ? "unknown" : new ArrayList<>(after.keySet()));
        }
    }

    /** Overload without logger — used by packet injector when logger is supplied separately. */
    static void filterRoot(Object rootObj, Player player, PluginConfig config) {
        filterRoot(rootObj, player, config, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void filterRootFallback(RootCommandNode root, Player player, PluginConfig config, Logger log) {
        Collection<CommandNode<?>> children = root.getChildren();
        if (log != null) log.info("[TC][fallback] Using public API. Children count: {}", children.size());

        List<CommandNode> toKeep = new ArrayList<>();
        List<String> toRemoveNames = new ArrayList<>();
        for (CommandNode child : new ArrayList<>(children)) {
            boolean visible = CommandFilter.isCommandVisibleToPlayer(config, child.getName().toLowerCase(), player::hasPermission);
            if (visible) toKeep.add(child);
            else toRemoveNames.add(child.getName());
        }
        if (log != null) log.info("[TC][fallback] keep={}, remove={}", toKeep.size(), toRemoveNames);

        // Try reflection clear first; fall back to public API clear
        boolean clearedViaReflection = false;
        for (String fn : new String[]{"children", "literals", "arguments"}) {
            Map<?, ?> m = getInternalMap(root, fn, log);
            if (m != null) { m.clear(); clearedViaReflection = true; }
        }
        if (!clearedViaReflection) {
            try {
                root.getChildren().clear();
                if (log != null) log.info("[TC][fallback] Cleared via getChildren().clear()");
            } catch (Exception e) {
                if (log != null) log.warn("[TC][fallback] getChildren().clear() failed: {}", e.getMessage());
            }
        }

        for (CommandNode child : toKeep) root.addChild(child);
        if (log != null) log.info("[TC][fallback] Done. Commands remaining: {}", root.getChildren().size());
    }

    @SuppressWarnings("unchecked")
    static <V> Map<String, V> getInternalMap(Object node, String fieldName, Logger log) {
        Field f = findField(node.getClass(), fieldName);
        if (f == null) {
            if (log != null) log.warn("[TC][reflect] Field '{}' not found in class hierarchy of {}",
                    fieldName, node.getClass().getName());
            return null;
        }
        try {
            f.setAccessible(true);
            Object val = f.get(node);
            if (val instanceof Map) {
                if (log != null) log.info("[TC][reflect] Field '{}' found in {} — type {}",
                        fieldName, f.getDeclaringClass().getSimpleName(), val.getClass().getSimpleName());
                return (Map<String, V>) val;
            }
            if (log != null) log.warn("[TC][reflect] Field '{}' is not a Map, got: {}",
                    fieldName, val == null ? "null" : val.getClass().getName());
            return null;
        } catch (Exception e) {
            if (log != null) log.warn("[TC][reflect] Error accessing field '{}': {}", fieldName, e.toString());
            return null;
        }
    }

    static void removeFromAllMaps(Object node, Set<String> names, Logger log) {
        for (String fn : new String[]{"children", "literals", "arguments"}) {
            Map<String, ?> map = getInternalMap(node, fn, null);
            if (map == null) {
                if (log != null) log.warn("[TC][reflect] removeFromAllMaps: field '{}' not accessible", fn);
                continue;
            }
            int before = map.size();
            try {
                map.keySet().removeAll(names);
            } catch (UnsupportedOperationException e) {
                // Map is unmodifiable — remove one-by-one via iterator
                Iterator<String> it = map.keySet().iterator();
                while (it.hasNext()) {
                    if (names.contains(it.next())) {
                        try { it.remove(); } catch (UnsupportedOperationException ignored) {}
                    }
                }
            }
            if (log != null) log.info("[TC][reflect] field '{}': {} -> {} entries", fn, before, map.size());
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
