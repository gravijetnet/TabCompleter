package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
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
            literals = CommandNode.class.getDeclaredField("literals");
            literals.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // Brigadier internals changed — DeclareCommands filtering unavailable
        }
        CHILDREN_FIELD = children;
        LITERALS_FIELD = literals;
    }

    public VelocityNativeListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        if (hasBypass(event.getPlayer())) return;
        if (CHILDREN_FIELD == null) return;

        try {
            RootCommandNode<CommandSource> root = event.getRootNode();

            @SuppressWarnings("unchecked")
            Map<String, CommandNode<CommandSource>> children =
                    (Map<String, CommandNode<CommandSource>>) CHILDREN_FIELD.get(root);
            @SuppressWarnings("unchecked")
            Map<String, ?> literals = (Map<String, ?>) LITERALS_FIELD.get(root);

            children.keySet().removeIf(name ->
                    CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name.toLowerCase()));
            literals.keySet().removeIf(name ->
                    CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name.toLowerCase()));
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warn("Could not filter declare-commands: {}", e.getMessage());
        }
    }

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        if (hasBypass(event.getPlayer())) return;

        event.getSuggestions().removeIf(text -> {
            String name = text.startsWith("/") ? text.substring(1) : text;
            if (name.contains(" ")) return false;
            return CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name.toLowerCase());
        });
    }

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
