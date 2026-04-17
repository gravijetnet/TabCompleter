package net.gravijet.tabcompleter.bungeecord;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.bungee.factory.BungeePacketEventsBuilder;
import net.gravijet.tabcompleter.bungeecord.command.TabCompleterCommand;
import net.gravijet.tabcompleter.bungeecord.listeners.BungeeChatListener;
import net.gravijet.tabcompleter.bungeecord.listeners.BungeeTabPacketListener;
import net.gravijet.tabcompleter.core.ConfigLoader;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class BungeeMain extends Plugin {

    private static BungeeMain instance;
    private PluginConfig pluginConfig;
    private BungeeTabPacketListener tabPacketListener;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(BungeePacketEventsBuilder.build(this));
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

    private void saveDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getDataFolder().mkdirs();
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                getLogger().severe("Could not save default config.yml: " + e.getMessage());
            }
        }
    }

    public void loadConfiguration() {
        try {
            pluginConfig = ConfigLoader.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            getLogger().severe("Failed to load config.yml: " + e.getMessage());
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) pluginConfig = ConfigLoader.loadFromStream(in);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot load config", ex);
            }
        }
    }

    private void registerListeners() {
        getProxy().getPluginManager().unregisterListeners(this);
        if (tabPacketListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(tabPacketListener);
        }

        getProxy().getPluginManager().registerListener(this, new BungeeChatListener(this));

        tabPacketListener = new BungeeTabPacketListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(tabPacketListener);

        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().getPluginManager().registerCommand(this, new TabCompleterCommand(this));
    }

    public static BungeeMain getInstance() { return instance; }
    public PluginConfig getPluginConfig()  { return pluginConfig; }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
