package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PluginConfig {

    private final String prefix;
    private final String bypassPermission;
    private final String reloadPermission;
    private final String noPermissionMessage;
    private final List<String> blockedCommands;
    private final String spigotMode;
    private final Map<String, GroupConfig> groups;
    private final String serverBrand;

    public PluginConfig(String prefix,
                        String bypassPermission,
                        String reloadPermission,
                        String noPermissionMessage,
                        List<String> blockedCommands,
                        String spigotMode,
                        Map<String, GroupConfig> groups,
                        String serverBrand) {
        this.prefix              = prefix;
        this.bypassPermission    = bypassPermission;
        this.reloadPermission    = reloadPermission;
        this.noPermissionMessage = noPermissionMessage;
        this.blockedCommands     = Collections.unmodifiableList(blockedCommands);
        this.spigotMode          = spigotMode;
        this.groups              = Collections.unmodifiableMap(groups);
        this.serverBrand         = serverBrand;
    }

    public String getPrefix()                    { return prefix; }
    public String getBypassPermission()          { return bypassPermission; }
    public String getReloadPermission()          { return reloadPermission; }
    public String getNoPermissionMessage()       { return noPermissionMessage; }
    public List<String> getBlockedCommands()     { return blockedCommands; }
    public String getSpigotMode()                { return spigotMode; }
    public Map<String, GroupConfig> getGroups()  { return groups; }
    /** Returns the custom server brand for the F3 screen, or empty string if not configured. */
    public String getServerBrand()               { return serverBrand != null ? serverBrand : ""; }
}
