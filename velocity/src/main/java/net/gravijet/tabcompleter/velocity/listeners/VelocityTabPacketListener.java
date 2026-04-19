package net.gravijet.tabcompleter.velocity.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareCommands;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.gravijet.tabcompleter.velocity.VelocityMain;

import java.util.ArrayList;
import java.util.List;

public class VelocityTabPacketListener extends PacketListenerAbstract {

    private final VelocityMain plugin;

    public VelocityTabPacketListener(VelocityMain plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;
        if (hasBypass(player)) return;

        WrapperPlayClientTabComplete clientPacket = new WrapperPlayClientTabComplete(event);
        String text = clientPacket.getText();
        if (text == null || !text.startsWith("/")) return;

        String afterSlash = text.substring(1);

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (CommandFilter.isCommandBlocked(plugin.getPluginConfig(), baseCmd)) {
                event.setCancelled(true);
            }
            // base not blocked → let through, backend handles arg suggestions
            return;
        }

        // prefix typing (no space) → let through; DECLARE_COMMANDS already hides blocked
        // commands for 1.13+, and onPacketSend filters the TAB_COMPLETE response for older versions
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.DECLARE_COMMANDS) {
            filterDeclareCommands(event);
        } else if (event.getPacketType() == PacketType.Play.Server.TAB_COMPLETE) {
            filterTabCompleteResponse(event);
        }
    }

    private void filterDeclareCommands(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;
        if (hasBypass(player)) return;

        WrapperPlayServerDeclareCommands wrapper = new WrapperPlayServerDeclareCommands(event);
        List<Node> nodes = wrapper.getNodes();
        int rootIdx = wrapper.getRootIndex();

        if (rootIdx < 0 || rootIdx >= nodes.size()) return;

        Node root = nodes.get(rootIdx);
        List<Integer> children = root.getChildren();
        if (children == null || children.isEmpty()) return;

        List<Integer> filtered = new ArrayList<>();
        boolean changed = false;
        for (int idx : children) {
            if (idx < 0 || idx >= nodes.size()) { filtered.add(idx); continue; }
            Node child = nodes.get(idx);
            if ((child.getFlags() & Node.TYPE_MASK) != Node.TYPE_LITERAL) { filtered.add(idx); continue; }
            String name = child.getName().orElse(null);
            if (name == null) { filtered.add(idx); continue; }
            if (CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name.toLowerCase())) {
                changed = true;
            } else {
                filtered.add(idx);
            }
        }

        if (!changed) return;
        root.setChildren(filtered);
        event.markForReEncode(true);
    }

    private void filterTabCompleteResponse(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;
        if (hasBypass(player)) return;

        WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
        List<WrapperPlayServerTabComplete.CommandMatch> matches = wrapper.getCommandMatches();
        if (matches == null || matches.isEmpty()) return;

        List<WrapperPlayServerTabComplete.CommandMatch> filtered = new ArrayList<>();
        boolean changed = false;
        for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
            String text = match.getText();
            String name = text.startsWith("/") ? text.substring(1) : text;
            if (!name.contains(" ") && CommandFilter.isCommandBlocked(plugin.getPluginConfig(), name)) {
                changed = true;
            } else {
                filtered.add(match);
            }
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
