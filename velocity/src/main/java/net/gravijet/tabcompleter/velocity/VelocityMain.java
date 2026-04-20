package net.gravijet.tabcompleter.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import net.gravijet.tabcompleter.core.ConfigLoader;
import net.gravijet.tabcompleter.core.PluginConfig;
import net.gravijet.tabcompleter.velocity.command.TabCompleterCommand;
import net.gravijet.tabcompleter.velocity.listeners.VelocityCommandListener;
import net.gravijet.tabcompleter.velocity.listeners.VelocityNativeListener;
import net.gravijet.tabcompleter.velocity.listeners.VelocityTabPacketListener;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "tabcompleter",
        name = "TabCompleter",
        version = "2.0",
        description = "Restricts tab completion and command execution on Velocity.",
        authors = {"gravijet"}
)
public class VelocityMain {

    private static VelocityMain instance;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginContainer container;
    private PluginConfig pluginConfig;

    @Inject
    public VelocityMain(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, PluginContainer container) {
        this.server        = server;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
        this.container     = container;
        instance = this;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(server, container, logger, dataDirectory));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(false)
                .checkForUpdates(false);
        PacketEvents.getAPI().load();

        saveDefaultConfig();
        loadConfiguration();

        PacketEvents.getAPI().init();

        registerListeners();

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("velocitytabcompleter")
                        .aliases("vtc")
                        .plugin(this)
                        .build(),
                new TabCompleterCommand(this));

        logger.info("TabCompleter v2.0 enabled.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        logger.info("TabCompleter disabled.");
    }

    private void saveDefaultConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, configPath);
                }
            }
        } catch (IOException e) {
            logger.error("Could not save default config.yml: {}", e.getMessage());
        }
    }

    public void loadConfiguration() {
        try {
            pluginConfig = ConfigLoader.load(dataDirectory.resolve("config.yml").toFile());
        } catch (IOException e) {
            logger.error("Failed to load config.yml: {}", e.getMessage());
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    pluginConfig = ConfigLoader.loadFromStream(in);
                }
            } catch (IOException ex) {
                throw new RuntimeException("Cannot load config", ex);
            }
        }
    }

    private void registerListeners() {
        server.getEventManager().unregisterListeners(this);
        server.getEventManager().register(this, new VelocityCommandListener(this));
        server.getEventManager().register(this, new VelocityNativeListener(this));

        PacketEvents.getAPI().getEventManager().unregisterAllListeners();
        PacketEvents.getAPI().getEventManager().registerListener(new VelocityTabPacketListener(this));
    }

    public static VelocityMain getInstance()  { return instance; }
    public ProxyServer getServer()            { return server; }
    public Logger getLogger()                 { return logger; }
    public PluginConfig getPluginConfig()     { return pluginConfig; }
}
