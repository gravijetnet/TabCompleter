package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;

public final class PluginConfig {

    private final String prefix;
    private final String bypassPermission;
    private final String reloadPermission;
    private final String noPermissionMessage;
    private final List<String> blockedCommands;

    public PluginConfig(String prefix,
                        String bypassPermission,
                        String reloadPermission,
                        String noPermissionMessage,
                        List<String> blockedCommands) {
        this.prefix              = prefix;
        this.bypassPermission    = bypassPermission;
        this.reloadPermission    = reloadPermission;
        this.noPermissionMessage = noPermissionMessage;
        this.blockedCommands     = Collections.unmodifiableList(blockedCommands);
    }

    public String getPrefix()                    { return prefix; }
    public String getBypassPermission()          { return bypassPermission; }
    public String getReloadPermission()          { return reloadPermission; }
    public String getNoPermissionMessage()       { return noPermissionMessage; }
    public List<String> getBlockedCommands()     { return blockedCommands; }
}
