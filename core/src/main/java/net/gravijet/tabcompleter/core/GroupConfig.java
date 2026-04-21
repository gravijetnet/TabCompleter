package net.gravijet.tabcompleter.core;

import java.util.Collections;
import java.util.List;

public final class GroupConfig {

    private final String name;
    private final String permission;
    private final List<String> commands;
    private final List<String> inherits;

    public GroupConfig(String name, String permission, List<String> commands, List<String> inherits) {
        this.name      = name;
        this.permission = permission;
        this.commands  = Collections.unmodifiableList(commands);
        this.inherits  = Collections.unmodifiableList(inherits);
    }

    public String getName()            { return name; }
    public String getPermission()      { return permission; }
    public List<String> getCommands()  { return commands; }
    public List<String> getInherits()  { return inherits; }
}
