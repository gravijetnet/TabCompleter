package net.gravijet.tabcompleter.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class VelocityPacketInjector {

    private static final String HANDLER_NAME = "tabcompleter-commands";
    private static final String[] INJECT_BEFORE = {"handler", "minecraft-handler", "connection"};
    private static final String[] CONN_FIELD_NAMES = {"connection", "minecraftConnection", "playerConnection"};
    private static final String[] CHAN_FIELD_NAMES = {"channel", "ch", "nettyChannel"};

    private final VelocityMain plugin;

    private static final ConcurrentHashMap<Class<?>, Optional<Field>> CONN_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> CHAN_FIELD_CACHE = new ConcurrentHashMap<>();

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
        if (ch == null) return;
        // Pipeline modifications must run on the channel's event loop to be thread-safe.
        if (ch.eventLoop().inEventLoop()) {
            if (ch.pipeline().get(HANDLER_NAME) != null) ch.pipeline().remove(HANDLER_NAME);
        } else {
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(HANDLER_NAME) != null) ch.pipeline().remove(HANDLER_NAME);
            });
        }
    }

    private void inject(Player player) {
        Channel channel = getChannel(player);
        if (channel == null) {
            plugin.getLogger().warn("[TC] Could not obtain Netty channel for {}. Command filtering may be incomplete.", player.getUsername());
            return;
        }

        // Pipeline mutations must happen on the channel's I/O thread. PostLoginEvent and
        // ServerPostConnectEvent fire on async threads, so schedule the work on the event loop.
        channel.eventLoop().execute(() -> injectOnEventLoop(channel, player));
    }

    private void injectOnEventLoop(Channel channel, Player player) {
        try {
            CommandPacketHandler handler = new CommandPacketHandler(plugin, player);

            // Use replace() if the handler is already present to avoid the remove-then-add
            // race window where a command packet can pass through unfiltered.
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().replace(HANDLER_NAME, HANDLER_NAME, handler);
                return;
            }

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
        } catch (Exception e) {
            plugin.getLogger().warn("[TC] Pipeline injection failed for {}: {}", player.getUsername(), e.toString());
        }
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
            Class<?> playerClass = player.getClass();
            Optional<Field> cachedConnField = CONN_FIELD_CACHE.get(playerClass);
            if (cachedConnField == null) {
                Field found = null;
                for (String fieldName : CONN_FIELD_NAMES) {
                    Field f = findField(playerClass, fieldName);
                    if (f != null) { found = f; break; }
                }
                cachedConnField = Optional.ofNullable(found);
                CONN_FIELD_CACHE.put(playerClass, cachedConnField);
            }
            if (cachedConnField.isPresent()) {
                Field f = cachedConnField.get();
                f.setAccessible(true);
                conn = f.get(player);
            }

            if (conn == null) {
                plugin.getLogger().warn("[TC] Could not find connection field in {}.", playerClass.getName());
                return null;
            }

            Class<?> connClass = conn.getClass();
            Optional<Field> cachedChanField = CHAN_FIELD_CACHE.get(connClass);
            if (cachedChanField == null) {
                Field found = null;
                for (String fieldName : CHAN_FIELD_NAMES) {
                    Field f = findField(connClass, fieldName);
                    if (f != null) { found = f; break; }
                }
                cachedChanField = Optional.ofNullable(found);
                CHAN_FIELD_CACHE.put(connClass, cachedChanField);
            }
            if (cachedChanField.isPresent()) {
                Field f = cachedChanField.get();
                f.setAccessible(true);
                Object val = f.get(conn);
                if (val instanceof Channel) return (Channel) val;
            }

            plugin.getLogger().warn("[TC] Could not find Channel field in {}.", conn.getClass().getName());
            return null;

        } catch (Exception e) {
            plugin.getLogger().warn("[TC] getChannel error for {} ({}): {}",
                    player.getUsername(), e.getClass().getSimpleName(), e.getMessage());
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
                        String baseCmd = afterSlash.split(" ", 2)[0];
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
            String lower = msg.getClass().getSimpleName().toLowerCase();
            // Must contain "tabcomplete" and must NOT be a response packet.
            // The additional check that the class is NOT also a command/declare packet
            // prevents misidentifying modern packets as legacy request packets.
            return lower.contains("tabcomplete") && !lower.contains("response")
                    && !lower.contains("available") && !lower.contains("declare");
        }

        /**
         * Matches Velocity's internal packet class for the legacy (pre-1.13)
         * tab-complete response sent from the proxy to the client.
         * We require the class to actually contain a List field (checked via
         * findStringListRef) before acting, so unrecognised future packets that
         * happen to match the name heuristic are handled safely.
         */
        private boolean isLegacyTabCompleteResponse(Object msg) {
            String lower = msg.getClass().getSimpleName().toLowerCase();
            return lower.contains("tabcomplete") && !lower.contains("request")
                    && !lower.contains("available") && !lower.contains("declare");
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
                    String baseCmd = afterSlash.split(" ", 2)[0];
                    if (!CommandFilter.isCommandVisibleToPlayer(
                            plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                        applyReplacement(packet, ref, new ArrayList<>());
                    }
                    // else: command is allowed — leave argument suggestions intact.
                } else {
                    // Command-name completion (e.g. "/he<TAB>").
                    List filtered = buildFilteredCommandList(ref.list);
                    applyReplacement(packet, ref, filtered);
                }
            } else if (req != null) {
                // No leading slash: player-name / argument context — do not filter.
            } else {
                // No stored request: conservatively filter command-like entries.
                List filtered = buildFilteredCommandList(ref.list);
                applyReplacement(packet, ref, filtered);
            }
        }

        /**
         * Applies the replacement list to the packet's suggestion list.
         * First tries in-place mutation; if the list is unmodifiable, sets the
         * field on the packet to a fresh ArrayList.
         * Works for both {@code List<String>} and {@code List<Offer>}.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void applyReplacement(Object packet, StringListRef ref, List replacement) {
            List live = ref.list;
            try {
                live.clear();
                live.addAll(replacement);
            } catch (UnsupportedOperationException e) {
                // List is unmodifiable — replace the field reference on the packet.
                try {
                    ref.field.set(packet, new ArrayList<>(replacement));
                } catch (Exception ex) {
                    // Security-relevant: blocked commands will be visible to the player.
                    // Log via SLF4J so the message appears in the server log file.
                    org.slf4j.LoggerFactory.getLogger(VelocityPacketInjector.class)
                            .warn("[TC] Could not replace suggestion list on {} — commands may leak: {}",
                                    packet.getClass().getSimpleName(), ex.toString());
                }
            }
        }

        /**
         * Extracts the display text from a suggestion element.
         * Handles both plain {@code String} entries and Velocity's internal
         * {@code Offer} objects (which wrap a {@code value} String field).
         */
        private static String extractElementText(Object element) {
            if (element instanceof String) return (String) element;
            // Offer-like objects: find the first non-null String field (typically "value").
            for (Class<?> c = element.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(element);
                        if (val != null) return val;
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private List buildFilteredCommandList(List original) {
            List kept = new ArrayList<>();
            Predicate<String> visible =
                    CommandFilter.resolve(plugin.getPluginConfig(), player::hasPermission);
            for (Object entry : original) {
                String text = extractElementText(entry);
                if (text == null) {
                    kept.add(entry); // keep unknown entries unchanged
                    continue;
                }
                String name = text.startsWith("/") ? text.substring(1) : text;
                if (visible.test(name)) {
                    kept.add(entry);
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
         * accessible List field that contains String elements (or is empty).
         * If no {@code List<String>} is found, falls back to the first non-null
         * List field — this covers Velocity versions that store suggestions as
         * {@code List<Offer>} (where each Offer wraps a value String).
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static StringListRef findStringListRef(Object packet) {
            StringListRef fallback = null;
            for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(packet);
                        if (!(val instanceof List)) continue;
                        List list = (List) val;
                        if (list.isEmpty() || list.get(0) instanceof String) {
                            return new StringListRef(f, list); // prefer List<String>
                        }
                        if (fallback == null) {
                            fallback = new StringListRef(f, list); // remember first Offer-like list
                        }
                    } catch (Exception ignored) {}
                }
            }
            return fallback;
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
            // Use the cached field lookup to check for a RootCommandNode field.
            return findRootField(msg.getClass()) != null;
        }

        private void filterModernCommandPacket(Object packet) {
            try {
                Field rootField = findRootField(packet.getClass());
                if (rootField == null) return;

                rootField.setAccessible(true);
                Object root = rootField.get(packet);
                if (root == null) return;

                VelocityNativeListener.filterRoot(root, player, plugin.getPluginConfig(), plugin.getLogger());
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
            // Prefer fields whose name indicates they hold the partial command text.
            // If no named match is found, return null so the caller uses the conservative
            // "no stored request" branch rather than guessing from an unknown field.
            boolean hasAnyStringField = false;
            for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(packet);
                        if (val == null || val.isEmpty()) continue;
                        String fn = f.getName().toLowerCase();
                        if (fn.contains("text") || fn.contains("command") || fn.contains("partial") || fn.contains("input")) {
                            return val;
                        }
                        hasAnyStringField = true;
                    } catch (Exception ignored) {}
                }
            }
            if (hasAnyStringField) {
                plugin.getLogger().debug("[TC] extractPartialCommand: no named command field found on {}; treating as unknown context",
                        packet.getClass().getSimpleName());
            }
            return null;
        }

        private static final ConcurrentHashMap<Class<?>, Optional<Field>> ROOT_FIELD_CACHE = new ConcurrentHashMap<>();

        private static Field findRootField(Class<?> startClass) {
            Optional<Field> cached = ROOT_FIELD_CACHE.get(startClass);
            if (cached != null) return cached.orElse(null);

            Field found = null;
            outer:
            for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if ("RootCommandNode".equals(f.getType().getSimpleName())) { found = f; break outer; }
                }
            }
            if (found == null) {
                outer:
                for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        String n = f.getName();
                        if (n.equals("rootNode") || n.equals("root") || n.equals("commandTree")) { found = f; break outer; }
                    }
                }
            }
            ROOT_FIELD_CACHE.put(startClass, Optional.ofNullable(found));
            return found;
        }
    }
}
