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
import java.util.Set;

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

        if (player.hasPermission(plugin.getPluginConfig().getBypassPermission())) return;

        WrapperPlayClientTabComplete clientPacket = new WrapperPlayClientTabComplete(event);
        String text = clientPacket.getText();
        if (text == null || !text.startsWith("/")) return;

        String afterSlash = text.substring(1);

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            if (!plugin.getServer().getCommandManager().hasCommand(baseCmd)) return;
            Set<String> allowed = CommandFilter.buildAllowedSet(plugin.getPluginConfig(), player::hasPermission);
            if (!allowed.contains(baseCmd)) event.setCancelled(true);
            return;
        }

        String typed = afterSlash.toLowerCase();
        List<String> suggestions = CommandFilter.filterSuggestions(plugin.getPluginConfig(), player::hasPermission, typed);

        event.setCancelled(true);
        sendSuggestions(event, clientPacket, typed, suggestions);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.DECLARE_COMMANDS) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;
        if (player.hasPermission(plugin.getPluginConfig().getBypassPermission())) return;

        Set<String> allowed = CommandFilter.buildAllowedSet(plugin.getPluginConfig(), player::hasPermission);

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
            String lower = name.toLowerCase();
            if (plugin.getServer().getCommandManager().hasCommand(lower) && !allowed.contains(lower)) {
                changed = true;
            } else {
                filtered.add(idx);
            }
        }

        if (!changed) return;
        root.setChildren(filtered);
        event.markForReEncode(true);
    }

    private void sendSuggestions(PacketReceiveEvent event,
                                  WrapperPlayClientTabComplete clientPacket,
                                  String typed,
                                  List<String> suggestions) {
        ServerVersion ver = PacketEvents.getAPI().getServerManager().getVersion();
        List<WrapperPlayServerTabComplete.CommandMatch> matches = new ArrayList<>();

        if (ver.isNewerThanOrEquals(ServerVersion.V_1_13)) {
            int txId = clientPacket.getTransactionId().orElse(0);
            WrapperPlayServerTabComplete.CommandRange range =
                    new WrapperPlayServerTabComplete.CommandRange(1, 1 + typed.length());
            for (String s : suggestions) {
                matches.add(new WrapperPlayServerTabComplete.CommandMatch(s));
            }
            event.getUser().sendPacket(new WrapperPlayServerTabComplete(txId, range, matches));
        } else {
            for (String s : suggestions) {
                matches.add(new WrapperPlayServerTabComplete.CommandMatch("/" + s));
            }
            event.getUser().sendPacket(
                    new WrapperPlayServerTabComplete(null,
                            new WrapperPlayServerTabComplete.CommandRange(0, 0), matches));
        }
    }
}
