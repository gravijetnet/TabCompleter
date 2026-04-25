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

    private static final Field CHILDREN_FIELD;
    private static final Field LITERALS_FIELD;

    static {
        Field children = null, literals = null;
        try {
            children = CommandNode.class.getDeclaredField("children");
            children.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            literals = CommandNode.class.getDeclaredField("literals");
            literals.setAccessible(true);
        } catch (Exception ignored) {}
        CHILDREN_FIELD = children;
        LITERALS_FIELD = literals;
    }

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe(order = PostOrder.LATE)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        if (CHILDREN_FIELD == null) {
            plugin.getLogger().warn("TabCompleter: cannot filter DeclareCommands packet — " +
                    "Brigadier internal fields are inaccessible. Tab-completion blocking unavailable.");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            RootCommandNode<CommandSource> root = (RootCommandNode<CommandSource>) event.getRootNode();

            // Collect allowed children via public API before clearing
            List<CommandNode<CommandSource>> toKeep = new ArrayList<>();
            for (CommandNode<CommandSource> child : root.getChildren()) {
                String name = child.getName().toLowerCase();
                if (CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), name, player::hasPermission)) {
                    toKeep.add(child);
                }
            }

            // Clear internal maps via reflection
            @SuppressWarnings("unchecked")
            Map<String, ?> childrenMap = (Map<String, ?>) CHILDREN_FIELD.get(root);
            childrenMap.clear();

            if (LITERALS_FIELD != null) {
                @SuppressWarnings("unchecked")
                Map<String, ?> literalsMap = (Map<String, ?>) LITERALS_FIELD.get(root);
                literalsMap.clear();
            }

            // Re-add only the allowed children via public API
            // addChild() populates both children and literals/arguments maps correctly
            for (CommandNode<CommandSource> child : toKeep) {
                root.addChild(child);
            }
        } catch (Exception e) {
            plugin.getLogger().warn("TabCompleter: could not filter available commands: {}", e.getMessage());
        }
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) return;

        String partial = event.getPartialMessage();
        if (partial == null) return;

        String afterSlash = partial.startsWith("/") ? partial.substring(1) : partial;

        if (afterSlash.contains(" ")) {
            // Argument completion — clear all suggestions if base command is blocked.
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.getSuggestions().clear();
            }
        } else {
            // Command name completion — keep only visible suggestions.
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
