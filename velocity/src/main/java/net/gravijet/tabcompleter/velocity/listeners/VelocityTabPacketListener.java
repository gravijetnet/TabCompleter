package net.gravijet.tabcompleter.velocity.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
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
            Set<String> allowed = CommandFilter.buildAllowedSet(plugin.getPluginConfig(), player::hasPermission);
            if (!allowed.contains(baseCmd)) event.setCancelled(true);
            return;
        }

        String typed = afterSlash.toLowerCase();
        List<String> suggestions = CommandFilter.filterSuggestions(plugin.getPluginConfig(), player::hasPermission, typed);

        event.setCancelled(true);
        sendSuggestions(event, clientPacket, typed, suggestions);
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
