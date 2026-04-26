package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Injects a ChannelOutboundHandlerAdapter into each player's Netty pipeline
 * to intercept the AvailableCommandsPacket (DeclareCommands) before encoding.
 *
 * Pipeline position: right before "handler" (Velocity's MinecraftConnection).
 * Outbound flow:  handler → [OUR INTERCEPTOR] → minecraft-encoder → frame-encoder → socket
 * We therefore see decoded MinecraftPacket objects, not raw bytes.
 */
public class VelocityPacketInjector {

    private static final String HANDLER_NAME = "tabcompleter-commands";

    // Try these handler names in order when looking for the injection point.
    private static final String[] INJECT_BEFORE = {"handler", "minecraft-handler", "connection"};

    // Possible field names for the MinecraftConnection inside ConnectedPlayer.
    private static final String[] CONN_FIELD_NAMES = {"connection", "minecraftConnection", "playerConnection"};

    // Possible field names for the Channel inside MinecraftConnection.
    private static final String[] CHAN_FIELD_NAMES = {"channel", "ch", "nettyChannel"};

    // Write log limit — set to 0 to silence, >0 to log first N writes per inject.
    static final int MAX_WRITE_LOGS = 30;

    private final VelocityMain plugin;

    // Per-player write counter — reset on every inject() call.
    private final Map<UUID, AtomicInteger> writeCounters = new ConcurrentHashMap<>();

    public VelocityPacketInjector(VelocityMain plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("[TC][Netty] VelocityPacketInjector constructed.");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        plugin.getLogger().info("[TC][Netty] PostLoginEvent -> injecting for player={}", event.getPlayer().getUsername());
        inject(event.getPlayer());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().info("[TC][Netty] ServerPostConnectEvent -> re-injecting for player={}", player.getUsername());
        inject(player);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        writeCounters.remove(event.getPlayer().getUniqueId());
        Channel ch = getChannel(event.getPlayer());
        if (ch != null && ch.pipeline().get(HANDLER_NAME) != null) {
            ch.pipeline().remove(HANDLER_NAME);
        }
    }

    private void inject(Player player) {
        Channel channel = getChannel(player);
        if (channel == null) {
            plugin.getLogger().warn("[TC][Netty] Could not obtain Netty channel for {}. "
                    + "DeclareCommands filtering will rely on the event layer only.", player.getUsername());
            return;
        }

        List<String> pipelineNames = channel.pipeline().names();
        plugin.getLogger().info("[TC][Netty] Pipeline for {}: {}", player.getUsername(), pipelineNames);

        // Remove stale handler if present (e.g. server transfer).
        if (channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
            plugin.getLogger().info("[TC][Netty] Removed stale handler for player={}", player.getUsername());
        }

        // Reset write counter so we log the first 200 writes after (re-)injection.
        AtomicInteger counter = new AtomicInteger(0);
        writeCounters.put(player.getUniqueId(), counter);

        CommandPacketHandler handler = new CommandPacketHandler(plugin, player, counter);

        for (String before : INJECT_BEFORE) {
            if (channel.pipeline().get(before) != null) {
                channel.pipeline().addBefore(before, HANDLER_NAME, handler);
                plugin.getLogger().info("[TC][Netty] Injected before '{}' for player={}. Pipeline after: {}",
                        before, player.getUsername(), channel.pipeline().names());
                return;
            }
        }

        // Fallback: inject right after the minecraft-encoder so we are still in
        // the outbound path before bytes leave for the client.
        for (String name : pipelineNames) {
            if (name.contains("encoder")) {
                channel.pipeline().addAfter(name, HANDLER_NAME, handler);
                plugin.getLogger().info("[TC][Netty] Fallback: injected after encoder '{}' for player={}. Pipeline after: {}",
                        name, player.getUsername(), channel.pipeline().names());
                return;
            }
        }

        plugin.getLogger().warn("[TC][Netty] No injection point found for {}! Pipeline: {}",
                player.getUsername(), pipelineNames);
    }

    // Walk the class hierarchy to find a declared field by name.
    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    Channel getChannel(Player player) {
        try {
            // --- Step 1: find the MinecraftConnection ---
            Object conn = null;
            String usedConnField = null;
            for (String fieldName : CONN_FIELD_NAMES) {
                Field f = findField(player.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(player);
                if (val != null) {
                    conn = val;
                    usedConnField = fieldName;
                    break;
                }
            }

            if (conn == null) {
                plugin.getLogger().warn("[TC][Netty] Could not find connection field in {}. "
                        + "Tried: {}", player.getClass().getName(), List.of(CONN_FIELD_NAMES));
                return null;
            }
            plugin.getLogger().debug("[TC][Netty] Found connection via field '{}' (type={})",
                    usedConnField, conn.getClass().getName());

            // --- Step 2: find the Channel inside the connection ---
            for (String fieldName : CHAN_FIELD_NAMES) {
                Field f = findField(conn.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(conn);
                if (val instanceof Channel) {
                    plugin.getLogger().debug("[TC][Netty] Found channel via field '{}' in {}",
                            fieldName, conn.getClass().getName());
                    return (Channel) val;
                }
            }

            plugin.getLogger().warn("[TC][Netty] Could not find Channel field in {}. Tried: {}",
                    conn.getClass().getName(), List.of(CHAN_FIELD_NAMES));
            return null;

        } catch (Exception e) {
            plugin.getLogger().warn("[TC][Netty] getChannel error for {}: {}", player.getUsername(), e.toString());
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static final class CommandPacketHandler extends ChannelOutboundHandlerAdapter {

        private final VelocityMain plugin;
        private final Player player;
        private final AtomicInteger writeCounter;

        CommandPacketHandler(VelocityMain plugin, Player player, AtomicInteger writeCounter) {
            this.plugin = plugin;
            this.player = player;
            this.writeCounter = writeCounter;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            int count = writeCounter.incrementAndGet();
            // Log every write for the first MAX_WRITE_LOGS packets so we can see EXACTLY
            // what class names flow through after injection.
            if (count <= MAX_WRITE_LOGS) {
                plugin.getLogger().info("[TC][Netty] write#{} class='{}' player={}",
                        count, msg.getClass().getName(), player.getUsername());
            } else if (count == MAX_WRITE_LOGS + 1) {
                plugin.getLogger().info("[TC][Netty] write-log limit ({}) reached for player={}, silencing further write logs",
                        MAX_WRITE_LOGS, player.getUsername());
            }

            if (isCommandPacket(msg)) {
                plugin.getLogger().info("[TC][Netty] >>> COMMAND PACKET INTERCEPTED: class='{}' player={}",
                        msg.getClass().getName(), player.getUsername());
                filterPacket(msg);
            }
            super.write(ctx, msg, promise);
        }

        private boolean isCommandPacket(Object msg) {
            String name = msg.getClass().getSimpleName();
            String fullName = msg.getClass().getName();
            // Name-based check (covers most Velocity versions)
            if (name.contains("AvailableCommands") || name.contains("DeclareCommands")
                    || name.equals("CommandsPacket") || name.equals("Commands")
                    || fullName.contains("availablecommands") || fullName.contains("declarecommands")) {
                return true;
            }
            // Reflection fallback: if any field of type RootCommandNode exists, treat as command packet
            for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.contains("RootCommandNode") || typeName.contains("CommandNode")) {
                        return true;
                    }
                }
            }
            // If class name suggests commands, log it so we can add an explicit check.
            if (name.toLowerCase().contains("command")) {
                plugin.getLogger().warn("[TC][Netty] Possible command packet NOT matched: class='{}' player={}",
                        msg.getClass().getName(), player.getUsername());
            }
            return false;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void filterPacket(Object packet) {
            String bypassPerm = plugin.getPluginConfig().getBypassPermission();
            boolean bypass = player.hasPermission(bypassPerm) || player.hasPermission("*");
            plugin.getLogger().info("[TC][Netty] filterPacket player={} bypass={}", player.getUsername(), bypass);
            if (bypass) return;

            try {
                // Dump all fields of the packet class so we know what's available.
                StringBuilder fieldDump = new StringBuilder();
                for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        fieldDump.append(c.getSimpleName())
                                .append('.').append(f.getName())
                                .append(':').append(f.getType().getSimpleName())
                                .append(' ');
                    }
                }
                plugin.getLogger().info("[TC][Netty] Packet fields for {}: {}", packet.getClass().getSimpleName(), fieldDump);

                Field rootField = findRootField(packet.getClass());
                if (rootField == null) {
                    plugin.getLogger().warn("[TC][Netty] No RootCommandNode field found in {}",
                            packet.getClass().getName());
                    return;
                }

                plugin.getLogger().info("[TC][Netty] Root field: {}.{} (declaredType={})",
                        rootField.getDeclaringClass().getSimpleName(),
                        rootField.getName(),
                        rootField.getType().getSimpleName());

                rootField.setAccessible(true);
                Object root = rootField.get(packet);

                if (root == null) {
                    plugin.getLogger().warn("[TC][Netty] Root field '{}' is null for player={}",
                            rootField.getName(), player.getUsername());
                    return;
                }

                plugin.getLogger().info("[TC][Netty] Root class={} instanceof RootCommandNode={}",
                        root.getClass().getName(), root instanceof RootCommandNode);

                VelocityNativeListener.filterRoot(root, player, plugin.getPluginConfig(), plugin.getLogger());

            } catch (Exception e) {
                plugin.getLogger().warn("[TC][Netty] filterPacket exception player={}: {} — {}",
                        player.getUsername(), e.getClass().getName(), e.getMessage());
                e.printStackTrace();
            }
        }

        private static Field findRootField(Class<?> startClass) {
            // Pass 1: exact type match (most reliable across Velocity versions)
            for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("RootCommandNode")) return f;
                }
            }
            // Pass 2: name-based fallback
            for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    String n = f.getName();
                    if (n.equals("rootNode") || n.equals("root") || n.equals("commandTree")) return f;
                }
            }
            return null;
        }
    }
}
