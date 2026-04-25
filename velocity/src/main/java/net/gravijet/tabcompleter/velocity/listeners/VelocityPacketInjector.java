package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;

/**
 * Injects a ChannelOutboundHandlerAdapter into each player's Netty pipeline
 * to intercept the AvailableCommandsPacket (DeclareCommands) before encoding.
 *
 * This runs at the lowest possible level — completely independent of Velocity's
 * event system — so it catches every packet that reaches the client.
 *
 * Pipeline position: right before "handler" (Velocity's main connection handler).
 * In the outbound direction the write flows:
 *   handler → [our interceptor] → minecraft-encoder → framer → socket
 * so we see decoded MinecraftPacket objects, not raw bytes.
 */
public class VelocityPacketInjector {

    private static final String HANDLER_NAME = "tabcompleter-commands";

    // Velocity may rename the pipeline handlers across versions — try them in order.
    private static final String[] INJECT_BEFORE = {"handler", "minecraft-handler", "connection"};

    private final VelocityMain plugin;

    public VelocityPacketInjector(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        inject(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Channel ch = getChannel(event.getPlayer());
        if (ch != null && ch.pipeline().get(HANDLER_NAME) != null) {
            ch.pipeline().remove(HANDLER_NAME);
        }
    }

    private void inject(Player player) {
        Channel channel = getChannel(player);
        if (channel == null) {
            plugin.getLogger().warn("[TabCompleter] Netty inject: could not get channel for {}. "
                    + "DeclareCommands packet filtering will rely on the event layer only.",
                    player.getUsername());
            return;
        }

        if (channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
        }

        CommandPacketHandler handler = new CommandPacketHandler(plugin, player);

        for (String before : INJECT_BEFORE) {
            if (channel.pipeline().get(before) != null) {
                channel.pipeline().addBefore(before, HANDLER_NAME, handler);
                return;
            }
        }

        // Fallback: add right before the encoder so we still see packet objects.
        for (String name : channel.pipeline().names()) {
            if (name.contains("encoder")) {
                channel.pipeline().addAfter(name, HANDLER_NAME, handler);
                return;
            }
        }

        plugin.getLogger().warn("[TabCompleter] Netty inject: could not find injection point for {}. Pipeline: {}",
                player.getUsername(), channel.pipeline().names());
    }

    // Walk the class hierarchy looking for a field by name.
    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    Channel getChannel(Player player) {
        try {
            Field connField = findField(player.getClass(), "connection");
            if (connField == null) return null;
            connField.setAccessible(true);
            Object conn = connField.get(player);

            Field chanField = findField(conn.getClass(), "channel");
            if (chanField == null) return null;
            chanField.setAccessible(true);
            Object chan = chanField.get(conn);
            return chan instanceof Channel ? (Channel) chan : null;
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static final class CommandPacketHandler extends ChannelOutboundHandlerAdapter {

        private final VelocityMain plugin;
        private final Player player;

        CommandPacketHandler(VelocityMain plugin, Player player) {
            this.plugin = plugin;
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (isCommandPacket(msg)) {
                filterPacket(msg);
            }
            super.write(ctx, msg, promise);
        }

        private static boolean isCommandPacket(Object msg) {
            String name = msg.getClass().getSimpleName();
            // Velocity names this differently across versions — match any variant.
            return name.contains("AvailableCommands")
                    || name.contains("DeclareCommands")
                    || name.equals("CommandsPacket");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void filterPacket(Object packet) {
            // Check bypass before touching the packet.
            String bypassPerm = plugin.getPluginConfig().getBypassPermission();
            if (player.hasPermission(bypassPerm) || player.hasPermission("*")) return;

            try {
                // Find the field that holds the RootCommandNode.
                // Common names across Velocity versions: rootNode, root, commandTree.
                Field rootField = findRootField(packet.getClass());
                if (rootField == null) return;

                rootField.setAccessible(true);
                Object root = rootField.get(packet);

                if (root instanceof RootCommandNode) {
                    VelocityNativeListener.filterRoot(root, player, plugin.getPluginConfig());
                }
            } catch (Exception e) {
                plugin.getLogger().debug("[TabCompleter] Packet filter error for {}: {}",
                        player.getUsername(), e.getMessage());
            }
        }

        private static Field findRootField(Class<?> startClass) {
            // Pass 1: exact type match — most reliable across Velocity versions.
            for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("RootCommandNode")) return f;
                }
            }
            // Pass 2: name-based fallback.
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
