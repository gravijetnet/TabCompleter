package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
import java.util.List;

public class VelocityPacketInjector {

    private static final String HANDLER_NAME = "tabcompleter-commands";
    private static final String[] INJECT_BEFORE = {"handler", "minecraft-handler", "connection"};
    private static final String[] CONN_FIELD_NAMES = {"connection", "minecraftConnection", "playerConnection"};
    private static final String[] CHAN_FIELD_NAMES = {"channel", "ch", "nettyChannel"};

    private final VelocityMain plugin;

    public VelocityPacketInjector(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        inject(event.getPlayer());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
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
            plugin.getLogger().warn("[TC] Could not obtain Netty channel for {}. Command filtering may be incomplete.", player.getUsername());
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

        for (String name : channel.pipeline().names()) {
            if (name.contains("encoder")) {
                channel.pipeline().addAfter(name, HANDLER_NAME, handler);
                return;
            }
        }

        plugin.getLogger().warn("[TC] No injection point found for {}.", player.getUsername());
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    Channel getChannel(Player player) {
        try {
            Object conn = null;
            for (String fieldName : CONN_FIELD_NAMES) {
                Field f = findField(player.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(player);
                if (val != null) { conn = val; break; }
            }

            if (conn == null) {
                plugin.getLogger().warn("[TC] Could not find connection field in {}.", player.getClass().getName());
                return null;
            }

            for (String fieldName : CHAN_FIELD_NAMES) {
                Field f = findField(conn.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(conn);
                if (val instanceof Channel) return (Channel) val;
            }

            plugin.getLogger().warn("[TC] Could not find Channel field in {}.", conn.getClass().getName());
            return null;

        } catch (Exception e) {
            plugin.getLogger().warn("[TC] getChannel error for {}: {}", player.getUsername(), e.toString());
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static final class CommandPacketHandler extends ChannelDuplexHandler {

        private final VelocityMain plugin;
        private final Player player;

        CommandPacketHandler(VelocityMain plugin, Player player) {
            this.plugin = plugin;
            this.player = player;
        }

        // Intercept inbound packets (client → proxy).
        // For legacy clients (1.8.x) that send a tab-complete request for argument
        // completions of a blocked command, we drop the packet here so the backend
        // never receives it and never sends suggestions back.
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!hasBypass() && isTabCompleteRequestPacket(msg)) {
                String partial = extractPartialCommand(msg);
                if (partial != null && !partial.isEmpty()) {
                    String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;
                    if (afterSlash.contains(" ")) {
                        String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
                        if (!baseCmd.isEmpty() && !CommandFilter.isCommandVisibleToPlayer(
                                plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                            // Drop the request — client will receive no suggestions.
                            return;
                        }
                    }
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (isCommandPacket(msg)) {
                filterPacket(msg);
            }
            super.write(ctx, msg, promise);
        }

        private boolean isTabCompleteRequestPacket(Object msg) {
            String name = msg.getClass().getSimpleName();
            return name.contains("TabComplete") && !name.toLowerCase().contains("response");
        }

        private String extractPartialCommand(Object packet) {
            for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(packet);
                        if (val != null && !val.isEmpty()) return val;
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        private boolean isCommandPacket(Object msg) {
            String name = msg.getClass().getSimpleName();
            String fullName = msg.getClass().getName();
            if (name.contains("AvailableCommands") || name.contains("DeclareCommands")
                    || name.equals("CommandsPacket") || name.equals("Commands")
                    || fullName.contains("availablecommands") || fullName.contains("declarecommands")) {
                return true;
            }
            for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.contains("RootCommandNode") || typeName.contains("CommandNode")) {
                        return true;
                    }
                }
            }
            return false;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void filterPacket(Object packet) {
            if (hasBypass()) return;
            try {
                Field rootField = findRootField(packet.getClass());
                if (rootField == null) return;

                rootField.setAccessible(true);
                Object root = rootField.get(packet);
                if (root == null) return;

                VelocityNativeListener.filterRoot(root, player, plugin.getPluginConfig(), null);
            } catch (Exception e) {
                plugin.getLogger().warn("[TC] filterPacket error for {}: {}", player.getUsername(), e.getMessage());
            }
        }

        private boolean hasBypass() {
            String perm = plugin.getPluginConfig().getBypassPermission();
            return player.hasPermission(perm) || player.hasPermission("*");
        }

        private static Field findRootField(Class<?> startClass) {
            for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("RootCommandNode")) return f;
                }
            }
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
