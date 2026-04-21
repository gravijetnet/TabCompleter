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

    public static PluginConfig load(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return loadFromStream(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static PluginConfig loadFromStream(InputStream in) {
        Yaml yaml = new Yaml();
        Object raw = yaml.load(in);
        Map<String, Object> data = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.<String, Object>emptyMap();
        return parse(data);
    }

    @SuppressWarnings("unchecked")
    private static PluginConfig parse(Map<String, Object> data) {
        String prefix      = str(data, "prefix", "");
        String bypassPerm  = str(data, "bypass-permission", "tabcompleter.bypass");
        String reloadPerm  = str(data, "reload-permission",  "tabcompleter.reload");
        String noPermMsg   = str(data, "no-permission-message", "&cThis command does not exist.");
        List<String> blockedCmds = strList(data, "blocked-commands");
        String spigotMode  = str(data, "mode", "blocklist");
        Map<String, GroupConfig> groups = parseGroups(data);

        return new PluginConfig(prefix, bypassPerm, reloadPerm, noPermMsg, blockedCmds, spigotMode, groups);
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
