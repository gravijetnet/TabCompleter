package net.gravijet.tabcompleter.spigot;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.gravijet.tabcompleter.core.ConfigLoader;
import net.gravijet.tabcompleter.core.ConfigUpdater;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.gravijet.tabcompleter.spigot.listeners.BrandListener;
import net.gravijet.tabcompleter.spigot.listeners.CustomTabCompleter;
import net.gravijet.tabcompleter.spigot.listeners.ModernCommandSendListener;
import net.gravijet.tabcompleter.spigot.listeners.TabListener;
import net.gravijet.tabcompleter.spigot.listeners.TabPacketListener;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class SpigotMain extends JavaPlugin {

    private static volatile SpigotMain instance;
    private volatile PluginConfig pluginConfig;
    private TabListener tabListener;
    private TabPacketListener tabPacketListener;
    private Listener modernCommandSendListener;
    private Listener brandListener;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(false)
                .checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfiguration();
        PacketEvents.getAPI().init();
        registerBrandChannel("minecraft:brand");
        // Legacy channel name; rejected by modern Bukkit's channel validation.
        // A failure here must not abort plugin startup.
        registerBrandChannel("MC|Brand");
        registerListeners();
        getLogger().info("TabCompleter v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        getLogger().info("TabCompleter disabled.");
    }

    public void loadConfiguration() {
        reloadConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(configFile,
                    getClass().getResourceAsStream("/config.yml"),
                    msg -> getLogger().info(msg));
            pluginConfig = ConfigLoader.load(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to load config.yml: " + e.getMessage());
            pluginConfig = ConfigLoader.loadFromStream(getClass().getResourceAsStream("/config.yml"));
        }
    }

    private void registerListeners() {
        if (tabListener != null) HandlerList.unregisterAll(tabListener);
        if (modernCommandSendListener != null) HandlerList.unregisterAll(modernCommandSendListener);
        if (brandListener != null) HandlerList.unregisterAll(brandListener);
        if (tabPacketListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(tabPacketListener);
        }

        tabListener = new TabListener(this);
        getServer().getPluginManager().registerEvents(tabListener, this);

        brandListener = new BrandListener(this);
        getServer().getPluginManager().registerEvents(brandListener, this);

        if (isClassAvailable("org.bukkit.event.player.PlayerCommandSendEvent")) {
            registerModernCommandSendListener();
        }

        tabPacketListener = new TabPacketListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(tabPacketListener);

        if (getCommand("tabcompleter") != null) {
            getCommand("tabcompleter").setTabCompleter(new CustomTabCompleter(this));
        }
    }

    private void registerBrandChannel(String channel) {
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
        } catch (RuntimeException e) {
            getLogger().fine("Brand channel '" + channel + "' not registered: " + e.getMessage());
        }
    }

    private void registerModernCommandSendListener() {
        modernCommandSendListener = new ModernCommandSendListener(this);
        getServer().getPluginManager().registerEvents(modernCommandSendListener, this);
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tabcompleter")) return false;
        String prefix = color(pluginConfig.getPrefix());

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(pluginConfig.getReloadPermission())) {
                sender.sendMessage(prefix + ChatColor.RED + "No permission.");
                return true;
            }
            loadConfiguration();
            registerListeners();
            sender.sendMessage(prefix + ChatColor.GREEN + "Configuration reloaded.");
            return true;
        }

        sender.sendMessage(prefix + ChatColor.GOLD + "TabCompleter v"
                + getDescription().getVersion() + " by gravijet.");
        sender.sendMessage(prefix + ChatColor.GOLD + "Usage: /tabcompleter reload");
        return true;
    }

    public static SpigotMain getInstance()  { return instance; }
    public PluginConfig getPluginConfig()   { return pluginConfig; }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
