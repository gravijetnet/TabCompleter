package net.gravijet.tabcompleter.core;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {

    private ConfigLoader() {}

    /** Load for Spigot: mode defaults to "allowlist", reads allowed-commands, supports groups. */
    public static PluginConfig load(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return loadInternal(in, false);
        }
    }

    /** Load for Spigot from stream. */
    public static PluginConfig loadFromStream(InputStream in) {
        return loadInternal(in, false);
    }

    /** Load for proxy (BungeeCord/Velocity): always blocklist, reads blocked-commands, no groups. */
    public static PluginConfig loadProxy(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return loadInternal(in, true);
        }
    }

    /** Load for proxy from stream. */
    public static PluginConfig loadProxyFromStream(InputStream in) {
        return loadInternal(in, true);
    }

    @SuppressWarnings("unchecked")
    private static PluginConfig loadInternal(InputStream in, boolean isProxy) {
        Yaml yaml = new Yaml();
        Object raw = yaml.load(in);
        Map<String, Object> data = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.<String, Object>emptyMap();
        return parse(data, isProxy);
    }

    @SuppressWarnings("unchecked")
    private static PluginConfig parse(Map<String, Object> data, boolean isProxy) {
        String prefix     = str(data, "prefix", "");
        String bypassPerm = str(data, "bypass-permission", "tabcompleter.bypass");
        String reloadPerm = str(data, "reload-permission",  "tabcompleter.reload");
        String noPermMsg  = str(data, "no-permission-message", "&cThis command does not exist.");

        String mode;
        List<String> cmds;
        Map<String, GroupConfig> groups;

        if (isProxy) {
            mode   = "blocklist";
            cmds   = strList(data, "blocked-commands");
            groups = parseGroups(data);
        } else {
            mode = str(data, "mode", "allowlist");
            // Prefer "commands" (current name); fall back to "allowed-commands" then "blocked-commands" for compat.
            cmds = strList(data, "commands");
            if (cmds.isEmpty()) cmds = strList(data, "allowed-commands");
            if (cmds.isEmpty()) cmds = strList(data, "blocked-commands");
            groups = parseGroups(data);
        }

        String serverBrand = str(data, "server-brand", "");

        return new PluginConfig(prefix, bypassPerm, reloadPerm, noPermMsg, cmds, mode, groups, serverBrand);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GroupConfig> parseGroups(Map<String, Object> data) {
        Object raw = data.get("groups");
        if (!(raw instanceof Map)) return Collections.emptyMap();

        Map<?, ?> groupsMap = (Map<?, ?>) raw;
        Map<String, GroupConfig> result = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : groupsMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map)) continue;

            Map<String, Object> groupData = (Map<String, Object>) entry.getValue();
            String permission = str(groupData, "permission", "tabcompleter.group." + name);
            List<String> commands = strList(groupData, "commands");
            List<String> inherits = strList(groupData, "inherits");

            result.put(name, new GroupConfig(name, permission, commands, inherits));
        }
        return result;
    }

    private static String str(Map<String, Object> data, String key, String def) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val) : def;
    }

    private static List<String> strList(Map<String, Object> data, String key) {
        Object val = data.get(key);
        List<String> result = new ArrayList<>();
        if (val instanceof List) {
            for (Object o : (List<?>) val) {
                if (o != null) result.add(String.valueOf(o));
            }
        }
        return result;
    }
}
