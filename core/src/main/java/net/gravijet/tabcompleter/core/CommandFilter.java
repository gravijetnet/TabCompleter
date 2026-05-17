package net.gravijet.tabcompleter.core;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class CommandFilter {

    private CommandFilter() {}

    /**
     * Lowercases using a fixed locale. Using the JVM default locale here is a bug:
     * on a Turkish/Azeri server {@code "LIST".toLowerCase()} yields {@code "lıst"}
     * (dotless i), which no longer matches the configured {@code list} entry.
     */
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the visibility rule for a single player <em>once</em> so it can be
     * reused across many command checks (e.g. filtering a whole command list).
     * This avoids re-evaluating group permissions and rebuilding the allowed-command
     * set for every single command, which previously happened per command per
     * keystroke during tab completion.
     */
    public static Predicate<String> resolve(PluginConfig config, Predicate<String> hasPermission) {
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
                // Global 'commands' list acts as a base that all players can access,
                // regardless of which group they are in (allowlist mode only).
                if ("allowlist".equalsIgnoreCase(config.getSpigotMode())) {
                    for (String c : config.getBlockedCommands()) {
                        allowed.add(lower(c));
                    }
                }
                return cmd -> isCommandInSet(allowed, lower(cmd));
            }
        }
        return cmd -> !isCommandFiltered(config, cmd);
    }

    /**
     * Returns true if the command is visible/allowed for a player.
     * Convenience for single checks; for bulk filtering call {@link #resolve}
     * once and reuse the returned predicate.
     */
    public static boolean isCommandVisibleToPlayer(PluginConfig config, String cmd, Predicate<String> hasPermission) {
        return resolve(config, hasPermission).test(cmd);
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
            result.add(lower(cmd));
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
            String[] parts = cmd.split(":", 2);
            // "essentials" in set → allows "essentials:friend" (namespace prefix match only)
            // "version" does NOT allow "bukkit:version" — use exact name to avoid unwanted aliases
            if (set.contains(parts[0])) return true;
        }
        return false;
    }

    /** Returns true if the command should be blocked (pure blocklist check, namespace-prefix only). */
    public static boolean isCommandBlocked(PluginConfig config, String cmd) {
        String lower = lower(cmd);
        for (String blocked : config.getBlockedCommands()) {
            if (blocked.equalsIgnoreCase(lower)) return true;
            // namespace prefix: blocking "essentials" also blocks "essentials:friend"
            // but blocking "friend" does NOT block "essentials:friend"
            if (lower.contains(":") && blocked.equalsIgnoreCase(lower.split(":", 2)[0])) return true;
        }
        return false;
    }

    /**
     * Checks if a command is in an allowlist using exact match or namespace-prefix match.
     * Whitelisting "friend" does NOT allow "phoenix:friend" (no suffix match).
     * Whitelisting "essentials" DOES allow "essentials:friend" (namespace prefix).
     */
    private static boolean isCommandInAllowList(List<String> list, String cmd) {
        String lower = lower(cmd);
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
