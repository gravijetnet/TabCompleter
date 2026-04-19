package net.gravijet.tabcompleter.core;

public final class CommandFilter {

    private CommandFilter() {}

    public static boolean isCommandBlocked(PluginConfig config, String cmd) {
        String lower = cmd.toLowerCase();
        for (String blocked : config.getBlockedCommands()) {
            if (blocked.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }
}
