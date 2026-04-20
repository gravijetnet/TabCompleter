package net.gravijet.tabcompleter.core;

public final class CommandFilter {

    private CommandFilter() {}

    public static boolean isCommandBlocked(PluginConfig config, String cmd) {
        String lower = cmd.toLowerCase();
        for (String blocked : config.getBlockedCommands()) {
            if (blocked.equalsIgnoreCase(lower)) return true;
            // block "plugin:command" if "plugin" or "command" is blocked (e.g. velocity:callback blocked via velocity)
            if (lower.contains(":")) {
                String[] parts = lower.split(":", 2);
                if (blocked.equalsIgnoreCase(parts[0]) || blocked.equalsIgnoreCase(parts[1])) return true;
            }
        }
        return false;
    }

    /** Returns true if the command should be hidden/blocked for this player (Spigot only). */
    public static boolean isCommandFiltered(PluginConfig config, String cmd) {
        boolean inList = isCommandBlocked(config, cmd);
        return "blocklist".equalsIgnoreCase(config.getSpigotMode()) ? inList : !inList;
    }
}
