package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PluginConfig {

    private final String prefix;
    private final String bypassPermission;
    private final String reloadPermission;
    private final String noPermissionMessage;
    private final List<String> allowedCommands;
    private final Map<String, GroupData> groups;

    public PluginConfig(String prefix,
                        String bypassPermission,
                        String reloadPermission,
                        String noPermissionMessage,
                        List<String> allowedCommands,
                        Map<String, GroupData> groups) {
        this.prefix              = prefix;
        this.bypassPermission    = bypassPermission;
        this.reloadPermission    = reloadPermission;
        this.noPermissionMessage = noPermissionMessage;
        this.allowedCommands     = Collections.unmodifiableList(allowedCommands);
        this.groups              = Collections.unmodifiableMap(groups);
    }

    public String getPrefix()                    { return prefix; }
    public String getBypassPermission()          { return bypassPermission; }
    public String getReloadPermission()          { return reloadPermission; }
    public String getNoPermissionMessage()       { return noPermissionMessage; }
    public List<String> getAllowedCommands()     { return allowedCommands; }
    public Map<String, GroupData> getGroups()    { return groups; }
}
