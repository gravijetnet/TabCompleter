package net.gravijet.tabcompleter.velocity.listeners;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VelocityAvailableCommandsListener {

    private final VelocityMain plugin;

    public VelocityAvailableCommandsListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        Player player = event.getPlayer();
        String perm = plugin.getPluginConfig().getBypassPermission();
        if (player.hasPermission(perm) || player.hasPermission("*")) return;

        List<String> toRemove = new ArrayList<>();
        for (CommandNode<CommandSource> child : event.getRootNode().getChildren()) {
            if (CommandFilter.isCommandBlocked(plugin.getPluginConfig(), child.getName())) {
                toRemove.add(child.getName());
            }
        }

        for (String name : toRemove) {
            removeChild(event.getRootNode(), name);
        }
    }

    private static void removeChild(CommandNode<?> parent, String name) {
        try {
            Field childrenField = CommandNode.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ((Map<?, ?>) childrenField.get(parent)).remove(name);

            Field literalsField = CommandNode.class.getDeclaredField("literals");
            literalsField.setAccessible(true);
            ((Map<?, ?>) literalsField.get(parent)).remove(name);
        } catch (ReflectiveOperationException ignored) {}
    }
}
