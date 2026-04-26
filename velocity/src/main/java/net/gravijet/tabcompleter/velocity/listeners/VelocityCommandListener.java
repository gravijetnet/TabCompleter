package net.gravijet.tabcompleter.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class VelocityCommandListener {

    private final VelocityMain plugin;

    public VelocityCommandListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) return;

        Player player = (Player) event.getCommandSource();
        boolean bypass = hasBypass(player);
        plugin.getLogger().info("[TC][Cmd] CommandExecuteEvent: cmd='{}' player={} bypass={}",
                event.getCommand(), player.getUsername(), bypass);
        if (bypass) return;

        String cmd = event.getCommand().split(" ", 2)[0].toLowerCase();
        boolean visible = CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), cmd, player::hasPermission);
        plugin.getLogger().info("[TC][Cmd] '{}' visible={}", cmd, visible);
        if (visible) return;

        plugin.getLogger().info("[TC][Cmd] Blocking command '{}' for player={}", cmd, player.getUsername());
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(plugin.getPluginConfig().getPrefix()
                        + plugin.getPluginConfig().getNoPermissionMessage()));
    }

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
