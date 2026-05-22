package net.gravijet.tabcompleter.spigot.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class TabPacketListener extends PacketListenerAbstract {

    private final SpigotMain plugin;

    public TabPacketListener(SpigotMain plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) return;

        // BUG-14: use instanceof rather than a blind cast; getPlayer() can return non-Player on some PacketEvents versions
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (hasBypass(player)) return;

        WrapperPlayClientTabComplete clientPacket = new WrapperPlayClientTabComplete(event);
        String text = clientPacket.getText();
        if (text == null || !text.startsWith("/")) return;

        String afterSlash = text.substring(1);

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0];
            if (!CommandFilter.isCommandVisibleToPlayer(plugin.getPluginConfig(), baseCmd, player::hasPermission)) {
                event.setCancelled(true);
            }
        }
        // No-space case: partial command name — let through and rely on response filtering.
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.TAB_COMPLETE) return;

        // BUG-14: use instanceof guard on send path as well
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (hasBypass(player)) return;

        WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
        List<WrapperPlayServerTabComplete.CommandMatch> matches = wrapper.getCommandMatches();
        if (matches == null || matches.isEmpty()) return;

        List<WrapperPlayServerTabComplete.CommandMatch> filtered = new ArrayList<>();
        boolean changed = false;
        Predicate<String> visible = CommandFilter.resolve(plugin.getPluginConfig(), player::hasPermission);
        for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
            String text = match.getText();
            String name = text.startsWith("/") ? text.substring(1) : text;
            if (name.contains(" ")) {
                // BUG-15: argument completion — filter based on the base command visibility
                String baseCmd = name.split(" ", 2)[0];
                if (!visible.test(baseCmd)) {
                    changed = true;
                    continue;
                }
            } else if (!visible.test(name)) {
                changed = true;
                continue;
            }
            filtered.add(match);
        }

        if (changed) {
            wrapper.setCommandMatches(filtered);
            event.markForReEncode(true);
        }
    }

    private boolean hasBypass(Player player) {
        String perm = plugin.getPluginConfig().getBypassPermission();
        return player.hasPermission(perm) || player.hasPermission("*");
    }
}
