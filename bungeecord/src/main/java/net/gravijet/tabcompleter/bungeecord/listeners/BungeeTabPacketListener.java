package net.gravijet.tabcompleter.bungeecord.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.gravijet.tabcompleter.core.CommandFilter;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BungeeTabPacketListener extends PacketListenerAbstract {

    private final BungeeMain plugin;

    public BungeeTabPacketListener(BungeeMain plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getPlayer();
        if (player == null) return;

        if (player.hasPermission(plugin.getPluginConfig().getBypassPermission())) return;

        WrapperPlayClientTabComplete clientPacket = new WrapperPlayClientTabComplete(event);
        String text = clientPacket.getText();
        if (text == null || !text.startsWith("/")) return;

        String afterSlash = text.substring(1);

        if (afterSlash.contains(" ")) {
            String baseCmd = afterSlash.split(" ", 2)[0].toLowerCase();
            boolean isProxyCommand = plugin.getProxy().getPluginManager().getCommands()
                    .stream().anyMatch(e -> e.getKey().equalsIgnoreCase(baseCmd));
            if (!isProxyCommand) return;
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
