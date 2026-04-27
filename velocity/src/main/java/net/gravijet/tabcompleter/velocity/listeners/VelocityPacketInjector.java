package net.gravijet.tabcompleter.velocity.listeners;

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
import java.util.ArrayList;
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

        /**
         * The partial text from the last tab-complete request received from this client.
         * Used in write() to decide how to filter the corresponding legacy response.
         * Volatile because Netty may call channelRead and write on different threads.
         */
        private volatile String lastTabRequest = null;

        CommandPacketHandler(VelocityMain plugin, Player player) {
            this.plugin = plugin;
            this.player = player;
        }

        /**
         * Intercept inbound packets (client → proxy).
         *
         * For all tab-complete requests: record the partial message so the write()
         * handler knows the context when the response arrives.
         *
         * For argument-completion requests on a blocked command: drop the packet
         * entirely so the backend never processes it.
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!hasBypass() && isTabCompleteRequestPacket(msg)) {
                String partial = extractPartialCommand(msg);
                lastTabRequest = partial;

                if (partial != null && !partial.isEmpty()) {
                    String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;
                    if (afterSlash.contains(" ")) {
                        String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
                        if (!CommandFilter.isCommandVisibleToPlayer(
                                plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                            // Drop the request — client will receive no suggestions.
                            return;
                        }
                    }
                }
            }
            super.channelRead(ctx, msg);
        }

        /**
         * Intercept outbound packets (proxy → client).
         *
         * Modern clients (1.13+): filter the Brigadier RootCommandNode inside
         * AvailableCommands / DeclareCommands packets.
         *
         * Legacy clients (pre-1.13, e.g. 1.8.8): filter the List<String> inside
         * the TabCompleteResponse packet using the context stored from the request.
         */
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!hasBypass()) {
                if (isCommandPacket(msg)) {
                    filterModernCommandPacket(msg);
                } else if (isLegacyTabCompleteResponse(msg)) {
                    filterLegacyTabCompleteResponse(msg);
                }
            }
            super.write(ctx, msg, promise);
        }

        // ------------------------------------------------------------------
        // Legacy tab-complete packet detection
        // ------------------------------------------------------------------

        private boolean isTabCompleteRequestPacket(Object msg) {
            String name = msg.getClass().getSimpleName();
            return name.contains("TabComplete") && !name.toLowerCase().contains("response");
        }

        /**
         * Matches Velocity's internal packet class for the legacy (pre-1.13)
         * tab-complete response sent from the proxy to the client.
         * Typical class names: "TabCompleteResponse", "LegacyTabCompleteResponse".
         */
        private boolean isLegacyTabCompleteResponse(Object msg) {
            String simple = msg.getClass().getSimpleName();
            String lower  = simple.toLowerCase();
            return lower.contains("tabcomplete") && lower.contains("response");
        }

        // ------------------------------------------------------------------
        // Legacy tab-complete response filtering
        // ------------------------------------------------------------------

        /**
         * Finds the List<String> of suggestions inside the legacy TabCompleteResponse
         * and removes entries the player should not see, based on the stored request context.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void filterLegacyTabCompleteResponse(Object packet) {
            StringListRef ref = findStringListRef(packet);
            if (ref == null) return;

            String req = lastTabRequest;

            if (req != null && req.startsWith("/")) {
                String afterSlash = req.substring(1);
                if (afterSlash.contains(" ")) {
                    // Argument completion for a specific command.
                    String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
                    if (!CommandFilter.isCommandVisibleToPlayer(
                            plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                        applyReplacement(packet, ref, new ArrayList<>());
                    }
                    // else: command is allowed — leave argument suggestions intact.
                } else {
                    // Command-name completion (e.g. "/he<TAB>").
                    List<String> filtered = buildFilteredCommandList(ref.list);
                    applyReplacement(packet, ref, filtered);
                }
            } else if (req != null) {
                // No leading slash: player-name / argument context — do not filter.
            } else {
                // No stored request: conservatively filter command-like entries.
                List<String> filtered = buildFilteredCommandList(ref.list);
                applyReplacement(packet, ref, filtered);
            }
        }

        /**
         * Applies the replacement list to the packet's suggestion list.
         * First tries in-place mutation; if the list is unmodifiable, sets the
         * field on the packet to a fresh ArrayList.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void applyReplacement(Object packet, StringListRef ref, List<String> replacement) {
            List live = ref.list;
            try {
                live.clear();
                live.addAll(replacement);
            } catch (UnsupportedOperationException e) {
                // List is unmodifiable — replace the field reference on the packet.
                try {
                    ref.field.set(packet, new ArrayList<>(replacement));
                } catch (Exception ignored) {}
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<String> buildFilteredCommandList(List original) {
            List<String> kept = new ArrayList<>();
            for (Object entry : original) {
                if (!(entry instanceof String)) continue;
                String text = (String) entry;
                String name = text.startsWith("/") ? text.substring(1) : text;
                // Accept both "name" and "namespace:name" forms.
                String baseName = name.contains(":") ? name.split(":", 2)[1] : name;
                if (CommandFilter.isCommandVisibleToPlayer(
                        plugin.getPluginConfig(), name.toLowerCase(), player::hasPermission)
                    || CommandFilter.isCommandVisibleToPlayer(
                        plugin.getPluginConfig(), baseName.toLowerCase(), player::hasPermission)) {
                    kept.add(text);
                }
            }
            return kept;
        }

        // ------------------------------------------------------------------
        // Find the List<String> field inside a legacy TabCompleteResponse
        // ------------------------------------------------------------------

        private static final class StringListRef {
            final Field field;
            @SuppressWarnings("rawtypes")
            final List list;
            @SuppressWarnings("rawtypes")
            StringListRef(Field field, List list) {
                this.field = field;
                this.list  = list;
            }
        }

        /**
         * Walks the class hierarchy of a packet object and returns the first
         * accessible List field that contains String elements (or is empty),
         * together with the Field itself so we can replace the reference if needed.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static StringListRef findStringListRef(Object packet) {
            for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(packet);
                        if (!(val instanceof List)) continue;
                        List list = (List) val;
                        if (list.isEmpty() || list.get(0) instanceof String) {
                            return new StringListRef(f, list);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        // ------------------------------------------------------------------
        // Modern command-packet filtering (1.13+ DeclareCommands / AvailableCommands)
        // ------------------------------------------------------------------

        private boolean isCommandPacket(Object msg) {
            String name     = msg.getClass().getSimpleName();
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

        private void filterModernCommandPacket(Object packet) {
            try {
                Field rootField = findRootField(packet.getClass());
                if (rootField == null) return;

                rootField.setAccessible(true);
                Object root = rootField.get(packet);
                if (root == null) return;

                VelocityNativeListener.filterRoot(root, player, plugin.getPluginConfig(), null);
            } catch (Exception e) {
                plugin.getLogger().warn("[TC] filterModernCommandPacket error for {}: {}", player.getUsername(), e.getMessage());
            }
        }

        // ------------------------------------------------------------------
        // Shared helpers
        // ------------------------------------------------------------------

        private boolean hasBypass() {
            String perm = plugin.getPluginConfig().getBypassPermission();
            return player.hasPermission(perm) || player.hasPermission("*");
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
