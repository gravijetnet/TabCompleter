package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VelocityNativeListener {

    private final VelocityMain plugin;

    // Brigadier internal fields — needed to clear and rebuild the root node in-place
    private static final Field CHILDREN_FIELD;
    private static final Field LITERALS_FIELD;

    // Velocity event field — alternative: swap the root node reference directly
    private static final Field EVENT_ROOT_FIELD;

    static {
        Field children = null, literals = null, eventRoot = null;
        try {
            children = CommandNode.class.getDeclaredField("children");
            children.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            literals = CommandNode.class.getDeclaredField("literals");
            literals.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            eventRoot = PlayerAvailableCommandsEvent.class.getDeclaredField("rootNode");
            eventRoot.setAccessible(true);
        } catch (Exception ignored) {}
        CHILDREN_FIELD = children;
        LITERALS_FIELD = literals;
        EVENT_ROOT_FIELD = eventRoot;
    }

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    /**
     * Filters the DeclareCommands packet sent to the player.
     * Runs LAST so all other plugins have already contributed their commands.
     *
     * Strategy A (preferred): build a fresh RootCommandNode with only the allowed
     * commands and swap it into the event via reflection on the event's own field.
     * This avoids touching Brigadier internals entirely.
     *
     * Strategy B (fallback): clear the original root's internal maps via reflection,
     * then re-populate them using the public addChild() API.
     */
    @Subscribe(order = PostOrder.LAST)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        @SuppressWarnings("unchecked")
        RootCommandNode<CommandSource> original = (RootCommandNode<CommandSource>) event.getRootNode();

        // Collect only the allowed children using the public API
        List<CommandNode<CommandSource>> toKeep = new ArrayList<>();
        for (CommandNode<CommandSource> child : original.getChildren()) {
            String name = child.getName().toLowerCase();
            if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), name, player::hasPermission)) {
                toKeep.add(child);
            }
        }

        // --- Strategy A: replace the event's root node with a fresh filtered one ---
        if (EVENT_ROOT_FIELD != null) {
            try {
                RootCommandNode<CommandSource> filtered = new RootCommandNode<>();
                for (CommandNode<CommandSource> child : toKeep) {
                    filtered.addChild(child);
                }
                EVENT_ROOT_FIELD.set(event, filtered);
                return;
            } catch (Exception ignored) {}
        }

        // --- Strategy B: clear original maps via reflection, re-add via public API ---
        if (CHILDREN_FIELD == null) {
            plugin.getLogger().warn("TabCompleter: cannot filter DeclareCommands — " +
                    "Brigadier fields and event field are both inaccessible. " +
                    "Tab-completion blocking unavailable for command names.");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, ?> childrenMap = (Map<String, ?>) CHILDREN_FIELD.get(original);
            childrenMap.clear();

            if (LITERALS_FIELD != null) {
                @SuppressWarnings("unchecked")
                Map<String, ?> literalsMap = (Map<String, ?>) LITERALS_FIELD.get(original);
                literalsMap.clear();
            }

            for (CommandNode<CommandSource> child : toKeep) {
                original.addChild(child);
            }
        } catch (Exception e) {
            plugin.getLogger().warn("TabCompleter: could not filter available commands: {}", e.getMessage());
        }
    }

    /**
     * Filters tab-complete suggestions for argument completions.
     * In 1.13+ with Brigadier, command-name completion is handled via DeclareCommands
     * (see onAvailableCommands above). This handler covers argument completions and
     * legacy command-name completions.
     */
    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            // Argument completion — clear all suggestions if the base command is blocked
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.getSuggestions().clear();
            }
        } else {
            // Command-name completion fallback (legacy clients / legacy backends)
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
