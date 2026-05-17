package net.gravijet.tabcompleter.core;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Keeps user config files up-to-date across plugin versions:
 *   - appends keys present in the bundled default but absent in the user file
 *   - applies key renames so user values survive between plugin updates
 *
 * The file is written back only when something actually changed.
 * User-customized values and comments are always preserved.
 */
public final class ConfigUpdater {

    /**
     * Key renames: oldKey -> newKey.
     * Add entries here when a config key is renamed in a new plugin version.
     * A rename is only applied when the old key exists and the new key does not.
     */
    private static final Map<String, String> RENAMES;
    static {
        RENAMES = new LinkedHashMap<>();
        // Example – uncomment when a rename is shipped:
        // RENAMES.put("old-key-name", "new-key-name");
    }

    private ConfigUpdater() {}

    /**
     * Updates the user's config file: applies renames and fills in missing defaults.
     * Writes the file back only if something changed.
     *
     * @param file          the config.yml on disk
     * @param defaultStream the bundled default config.yml (will be closed by this method)
     * @param log           receives a message for each key added or renamed
     * @return true if the file was modified
     */
    public static boolean update(File file, InputStream defaultStream, Consumer<String> log) throws IOException {
        if (defaultStream == null) {
            // No bundled default available — nothing to migrate or back-fill.
            return false;
        }
        String userText;
        try (InputStream in = new FileInputStream(file)) {
            userText = readAll(in);
        }
        String defaultText;
        try (InputStream in = defaultStream) {
            defaultText = readAll(in);
        }

        Map<String, Object> userData    = parseMap(userText);
        Map<String, Object> defaultData = parseMap(defaultText);
        boolean modified = false;

        // --- Apply renames ---
        for (Map.Entry<String, String> rename : RENAMES.entrySet()) {
            String oldKey = rename.getKey();
            String newKey = rename.getValue();
            if (userData.containsKey(oldKey) && !userData.containsKey(newKey)) {
                userText = renameTopLevelKey(userText, oldKey, newKey);
                log.accept("Migrated config key '" + oldKey + "' -> '" + newKey + "'");
                modified = true;
            }
        }

        // Re-parse after any renames so key presence checks below are accurate
        if (modified) userData = parseMap(userText);

        // --- Append missing top-level keys ---
        for (String key : defaultData.keySet()) {
            if (!userData.containsKey(key)) {
                String block = extractBlock(defaultText, key);
                if (!block.isEmpty()) {
                    if (!userText.endsWith("\n")) userText += "\n";
                    userText += "\n" + block;
                    userData = parseMap(userText); // keep map in sync for subsequent checks
                    log.accept("Added missing config key '" + key + "' with default value.");
                    modified = true;
                }
            }
        }

        if (modified) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(userText);
            }
        }
        return modified;
    }

    /**
     * Extracts the YAML block for a given top-level key from config text,
     * including the comment lines that immediately precede it.
     * Indented continuation lines (multi-line values, lists, nested maps) are included.
     */
    static String extractBlock(String text, String key) {
        String[] lines = text.split("\r?\n", -1);

        // Find the line where this top-level key starts
        int keyLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isTopLevelKeyLine(lines[i], key)) { keyLine = i; break; }
        }
        if (keyLine == -1) return "";

        // Walk backwards to collect comment / blank lines that precede the key
        int start = keyLine;
        while (start > 0) {
            String prev = lines[start - 1];
            if (prev.startsWith("#") || prev.trim().isEmpty()) start--;
            else break;
        }
        // Drop any leading blank lines from the collected range
        while (start < keyLine && lines[start].trim().isEmpty()) start++;

        // Walk forwards past indented continuation lines (list items, sub-keys, etc.)
        int end = keyLine + 1;
        while (end < lines.length && (lines[end].startsWith(" ") || lines[end].startsWith("\t"))) {
            end++;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Renames a top-level key in the raw YAML text, preserving its value and indentation.
     */
    private static String renameTopLevelKey(String text, String oldKey, String newKey) {
        return text.replaceFirst(
            "(?m)^" + java.util.regex.Pattern.quote(oldKey) + "(?=\\s*:)",
            newKey
        );
    }

    /** Returns true iff {@code line} is a top-level YAML key matching {@code key}. */
    private static boolean isTopLevelKeyLine(String line, String key) {
        if (line.length() <= key.length()) return false;
        if (!line.startsWith(key)) return false;
        char next = line.charAt(key.length());
        return next == ':' || next == ' ';
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String text) {
        Yaml yaml = new Yaml();
        Object raw = yaml.load(text);
        return (raw instanceof Map) ? (Map<String, Object>) raw : new LinkedHashMap<>();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
