package net.gravijet.tabcompleter.spigot.listeners;

import net.gravijet.tabcompleter.core.BrandUtil;
import net.gravijet.tabcompleter.spigot.SpigotMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class BrandListener implements Listener {

    private final SpigotMain plugin;
    private final String channel;

    public BrandListener(SpigotMain plugin) {
        this.plugin = plugin;
        // MC|Brand for pre-1.13, minecraft:brand for 1.13+
        this.channel = isLegacy() ? "MC|Brand" : "minecraft:brand";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        String raw = plugin.getPluginConfig().getServerBrand();
        if (raw.isEmpty()) return;
        Player player = event.getPlayer();
        byte[] payload = BrandUtil.buildPayload(BrandUtil.translateColors(raw));
        player.sendPluginMessage(plugin, channel, payload);
    }

    private static boolean isLegacy() {
        try {
            Class.forName("org.bukkit.event.player.PlayerCommandSendEvent");
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }
}
