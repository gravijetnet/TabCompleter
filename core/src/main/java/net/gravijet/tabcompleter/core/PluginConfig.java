package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PluginConfig {

    private final String prefix;
    private final String bypassPermission;
    private final String reloadPermission;
    private final String noPermissionMessage;
    // BUG-01: renamed from blockedCommands — this list holds the global commands list
    // whose semantic depends on mode: in allowlist mode it is the allowed-commands list,
    // in blocklist mode it is the blocked-commands list.
    private final List<String> commands;
    private final String spigotMode;
    private final Map<String, GroupConfig> groups;
    private final String serverBrand;

    public PluginConfig(String prefix,
                        String bypassPermission,
                        String reloadPermission,
                        String noPermissionMessage,
                        List<String> commands,
                        String spigotMode,
                        Map<String, GroupConfig> groups,
                        String serverBrand) {
        this.prefix              = prefix;
        this.bypassPermission    = bypassPermission;
        this.reloadPermission    = reloadPermission;
        this.noPermissionMessage = noPermissionMessage;
        this.commands            = Collections.unmodifiableList(commands);
        this.spigotMode          = spigotMode;
        this.groups              = Collections.unmodifiableMap(groups);
        this.serverBrand         = serverBrand;
    }

    public String getPrefix()                    { return prefix; }
    public String getBypassPermission()          { return bypassPermission; }
    public String getReloadPermission()          { return reloadPermission; }
    public String getNoPermissionMessage()       { return noPermissionMessage; }
    /** Returns the global commands list. Semantics depend on mode: allowlist = allowed cmds, blocklist = blocked cmds. */
    public List<String> getCommands()            { return commands; }
    public String getSpigotMode()                { return spigotMode; }
    public Map<String, GroupConfig> getGroups()  { return groups; }
    /** Returns the custom server brand for the F3 screen, or empty string if not configured. */
    public String getServerBrand()               { return serverBrand != null ? serverBrand : ""; }
}
