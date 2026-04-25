package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
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

    // Brigadier internal maps — needed to remove commands from the tree in-place.
    // The public API has addChild() but no removeChild(), so reflection is the only option.
    // RootCommandNode.rootNode is final, so we cannot swap the node itself; we must mutate it.
    private static final Field CHILDREN_FIELD;
    private static final Field LITERALS_FIELD;
    private static final Field ARGUMENTS_FIELD;

    static {
        Field children = null, literals = null, arguments = null;
        try {
            children = CommandNode.class.getDeclaredField("children");
            children.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            literals = CommandNode.class.getDeclaredField("literals");
            literals.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            arguments = CommandNode.class.getDeclaredField("arguments");
            arguments.setAccessible(true);
        } catch (Exception ignored) {}
        CHILDREN_FIELD = children;
        LITERALS_FIELD = literals;
        ARGUMENTS_FIELD = arguments;
    }

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    /**
     * Filters the DeclareCommands packet sent to the player by mutating the Brigadier
     * root node in-place. Runs LAST so all other plugins have contributed their commands.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Subscribe(order = PostOrder.LAST)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        RootCommandNode root = event.getRootNode();

        // Collect only the allowed children before we start modifying the tree.
        List<CommandNode> toKeep = new ArrayList<>();
        for (Object child : root.getChildren()) {
            CommandNode node = (CommandNode) child;
            String name = node.getName().toLowerCase();
            if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), name, player::hasPermission)) {
                toKeep.add(node);
            }
        }

        if (CHILDREN_FIELD == null) {
            plugin.getLogger().warn("[TabCompleter] Cannot filter tab-completion: Brigadier 'children' "
                    + "field is inaccessible. Blocked commands will still appear in tab-completion.");
            return;
        }

        try {
            // Clear all three internal maps so blocked commands vanish completely.
            ((Map<?, ?>) CHILDREN_FIELD.get(root)).clear();
            if (LITERALS_FIELD  != null) ((Map<?, ?>) LITERALS_FIELD.get(root)).clear();
            if (ARGUMENTS_FIELD != null) ((Map<?, ?>) ARGUMENTS_FIELD.get(root)).clear();

            // Re-populate with only the allowed commands.
            for (CommandNode child : toKeep) {
                root.addChild(child);
            }
        } catch (Exception e) {
            plugin.getLogger().warn("[TabCompleter] Failed to filter DeclareCommands packet: " + e.getMessage());
        }
    }

    /**
     * Filters tab-complete suggestions for argument completions and legacy
     * command-name completions (pre-1.13 clients / non-Brigadier backends).
     */
    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            // Argument completion — suppress all suggestions if the base command is blocked.
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.getSuggestions().clear();
            }
        } else {
            // Command-name completion fallback (legacy clients / legacy backends).
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
