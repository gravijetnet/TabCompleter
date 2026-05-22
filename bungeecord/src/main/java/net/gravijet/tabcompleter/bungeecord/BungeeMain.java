package net.gravijet.tabcompleter.bungeecord;

import net.gravijet.tabcompleter.bungeecord.command.TabCompleterCommand;
import net.gravijet.tabcompleter.bungeecord.listeners.BungeeBrandListener;
import net.gravijet.tabcompleter.bungeecord.listeners.BungeeChatListener;
import net.gravijet.tabcompleter.core.ConfigLoader;
import net.gravijet.tabcompleter.core.ConfigUpdater;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class BungeeMain extends Plugin {

    private static volatile BungeeMain instance;
    private volatile PluginConfig pluginConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        registerListeners();
        // BUG-16: set instance only after full initialisation
        instance = this;
        getLogger().info("TabCompleter v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
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
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(configFile,
                    getResourceAsStream("config.yml"),
                    msg -> getLogger().info(msg));
            pluginConfig = ConfigLoader.loadProxy(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to load config.yml: " + e.getMessage());
            // BUG-17: ensure pluginConfig is always set; warn explicitly if bundled resource is missing
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) {
                    pluginConfig = ConfigLoader.loadProxyFromStream(in);
                } else {
                    getLogger().severe("Bundled config.yml not found in JAR — filtering will be disabled!");
                    pluginConfig = ConfigLoader.loadProxyFromStream(null);
                }
            } catch (IOException ex) {
                throw new RuntimeException("Cannot load config", ex);
            }
        }
    }

    private void registerListeners() {
        // BUG-18: construct new listeners before unregistering the old ones to minimise
        // the window during which no listener is active on reload.
        BungeeChatListener chatListener   = new BungeeChatListener(this);
        BungeeBrandListener brandListener = new BungeeBrandListener(this);
        TabCompleterCommand command       = new TabCompleterCommand(this);
        getProxy().getPluginManager().unregisterListeners(this);
        getProxy().getPluginManager().registerListener(this, chatListener);
        getProxy().getPluginManager().registerListener(this, brandListener);
        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().getPluginManager().registerCommand(this, command);
    }

    public static BungeeMain getInstance() { return instance; }
    public PluginConfig getPluginConfig()  { return pluginConfig; }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
