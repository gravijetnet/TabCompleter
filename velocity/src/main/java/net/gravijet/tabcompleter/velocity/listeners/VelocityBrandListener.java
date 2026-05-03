package net.gravijet.tabcompleter.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.gravijet.tabcompleter.core.BrandUtil;
import net.gravijet.tabcompleter.velocity.VelocityMain;

public class VelocityBrandListener {

    private static final MinecraftChannelIdentifier BRAND_CHANNEL =
            MinecraftChannelIdentifier.from("minecraft:brand");

    private final VelocityMain plugin;

    public VelocityBrandListener(VelocityMain plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        String raw = plugin.getPluginConfig().getServerBrand();
        if (raw.isEmpty()) return;
        byte[] payload = BrandUtil.buildPayload(BrandUtil.translateColors(raw));
        event.getPlayer().sendPluginMessage(BRAND_CHANNEL, payload);
    }
}
