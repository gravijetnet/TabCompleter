package net.gravijet.tabcompleter.bungeecord.listeners;

import net.gravijet.tabcompleter.bungeecord.BungeeMain;
import net.gravijet.tabcompleter.core.BrandUtil;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public class BungeeBrandListener implements Listener {

    private final BungeeMain plugin;

    public BungeeBrandListener(BungeeMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onServerConnected(ServerConnectedEvent event) {
        String raw = plugin.getPluginConfig().getServerBrand();
        if (raw.isEmpty()) return;
        byte[] payload = BrandUtil.buildPayload(BrandUtil.translateColors(raw));
        event.getPlayer().sendData("minecraft:brand", payload);
    }
}
