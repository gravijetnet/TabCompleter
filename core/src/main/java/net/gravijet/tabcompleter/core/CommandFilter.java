package net.gravijet.tabcompleter.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CommandFilter {

    private CommandFilter() {}

    public static boolean isCommandAllowed(PluginConfig config, PermissionChecker checker, String cmd) {
        String lower = cmd.toLowerCase();
        for (String allowed : config.getAllowedCommands()) {
            if (allowed.equalsIgnoreCase(lower)) return true;
        }
        for (GroupData group : config.getGroups().values()) {
            if (checker.hasPermission(group.getPermission())) {
                for (String groupCmd : group.getCommands()) {
                    if (groupCmd.equalsIgnoreCase(lower)) return true;
                }
            }
        }
        return false;
    }

    public static Set<String> buildAllowedSet(PluginConfig config, PermissionChecker checker) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String cmd : config.getAllowedCommands()) {
            allowed.add(cmd.toLowerCase());
        }
        for (GroupData group : config.getGroups().values()) {
            if (checker.hasPermission(group.getPermission())) {
                for (String cmd : group.getCommands()) {
                    allowed.add(cmd.toLowerCase());
                }
            }
        }
        return allowed;
    }

    public static List<String> filterSuggestions(PluginConfig config, PermissionChecker checker, String typed) {
        Set<String> allowed = buildAllowedSet(config, checker);
        String lowerTyped = typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (String cmd : allowed) {
            if (cmd.startsWith(lowerTyped)) suggestions.add(cmd);
        }
        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        return suggestions;
    }
}
