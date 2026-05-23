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
        // PlayerCommandSendEvent was added in Bukkit 1.13, the same version that
        // replaced the "MC|Brand" channel with "minecraft:brand". Using the API class
        // as a proxy is correct, but checking the Bukkit version string directly is
        // more robust against backport builds that add the class without the channel.
        try {
            String version = org.bukkit.Bukkit.getBukkitVersion(); // e.g. "1.12.2-R0.1-SNAPSHOT"
            String[] parts = version.split("[-.]");
            if (parts.length < 2) throw new IllegalArgumentException("unexpected version format: " + version);
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major < 1 || (major == 1 && minor < 13);
        } catch (Exception e) {
            // Fall back to the class-presence check if version parsing fails.
            try {
                Class.forName("org.bukkit.event.player.PlayerCommandSendEvent");
                return false;
            } catch (ClassNotFoundException ex) {
                return true;
            }
        }
    }
}
