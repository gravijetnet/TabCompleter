package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;

public final class GroupData {

    private final String permission;
    private final List<String> commands;

    public GroupData(String permission, List<String> commands) {
        this.permission = permission;
        this.commands   = Collections.unmodifiableList(commands);
    }

    public String getPermission()     { return permission; }
    public List<String> getCommands() { return commands; }
}
