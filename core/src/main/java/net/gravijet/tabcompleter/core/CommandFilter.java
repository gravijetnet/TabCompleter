package net.gravijet.tabcompleter.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class CommandFilter {

    private CommandFilter() {}

    /**
     * Returns true if the command is visible/allowed for a player.
     * If groups are configured and the player has at least one group permission,
     * only commands in their groups (including inherited) are allowed.
     * Otherwise falls back to mode/blocked-commands logic.
     */
    public static boolean isCommandVisibleToPlayer(PluginConfig config, String cmd, Predicate<String> hasPermission) {
        if (!config.getGroups().isEmpty()) {
            boolean hasAnyGroup = false;
            for (GroupConfig g : config.getGroups().values()) {
                if (hasPermission.test(g.getPermission())) {
                    hasAnyGroup = true;
                    break;
                }
            }
            if (hasAnyGroup) {
                Set<String> allowed = collectAllowedCommands(config, hasPermission);
                return isCommandInSet(allowed, cmd.toLowerCase());
            }
        }
        return !isCommandFiltered(config, cmd);
    }

    private static Set<String> collectAllowedCommands(PluginConfig config, Predicate<String> hasPermission) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (GroupConfig group : config.getGroups().values()) {
            if (hasPermission.test(group.getPermission())) {
                collectGroupCommands(config, group, result, visited);
            }
        }
        return result;
    }

    private static void collectGroupCommands(PluginConfig config, GroupConfig group, Set<String> result, Set<String> visited) {
        if (!visited.add(group.getName())) return;
        for (String cmd : group.getCommands()) {
            result.add(cmd.toLowerCase());
        }
        for (String inheritName : group.getInherits()) {
            GroupConfig inherited = config.getGroups().get(inheritName);
            if (inherited != null) {
                collectGroupCommands(config, inherited, result, visited);
            }
        }
    }

    private static boolean isCommandInSet(Set<String> set, String cmd) {
        if (set.contains(cmd)) return true;
        if (cmd.contains(":")) {
            // Only match by namespace prefix (e.g. "phoenix" allows "phoenix:friend"),
            // never by suffix — so whitelisting "friend" does NOT allow "phoenix:friend".
            return set.contains(cmd.split(":", 2)[0]);
        }
        return false;
    }

    /** Legacy: Returns true if the command should be blocked (pure blocklist check). */
    public static boolean isCommandBlocked(PluginConfig config, String cmd) {
        String lower = cmd.toLowerCase();
        for (String blocked : config.getBlockedCommands()) {
            if (blocked.equalsIgnoreCase(lower)) return true;
            if (lower.contains(":")) {
                String[] parts = lower.split(":", 2);
                if (blocked.equalsIgnoreCase(parts[0]) || blocked.equalsIgnoreCase(parts[1])) return true;
            }
        }
        return false;
    }

    /**
     * Checks if a command is in an allowlist using exact match or namespace-prefix match.
     * Whitelisting "friend" does NOT allow "phoenix:friend" (no suffix match).
     * Whitelisting "essentials" DOES allow "essentials:friend" (namespace prefix).
     */
    private static boolean isCommandInAllowList(List<String> list, String cmd) {
        String lower = cmd.toLowerCase();
        for (String entry : list) {
            if (entry.equalsIgnoreCase(lower)) return true;
            if (lower.contains(":") && entry.equalsIgnoreCase(lower.split(":", 2)[0])) return true;
        }
        return false;
    }

    /** Returns true if the command should be hidden/blocked (respects mode). */
    public static boolean isCommandFiltered(PluginConfig config, String cmd) {
        if ("blocklist".equalsIgnoreCase(config.getSpigotMode())) {
            return isCommandBlocked(config, cmd);
        }
        // Allowlist mode: only exact or namespace-prefix match — no suffix match.
        return !isCommandInAllowList(config.getBlockedCommands(), cmd);
    }
}
