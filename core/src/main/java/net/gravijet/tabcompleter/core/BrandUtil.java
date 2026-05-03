package net.gravijet.tabcompleter.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class BrandUtil {

    private static final String COLOR_CHARS = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr";

    private BrandUtil() {}

    /**
     * Builds the raw bytes for a {@code minecraft:brand} plugin message payload.
     * The format is a Minecraft-protocol String: VarInt(byte length) + UTF-8 bytes.
     */
    public static byte[] buildPayload(String brand) {
        byte[] brandBytes = brand.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(brandBytes.length + 5);
        writeVarInt(baos, brandBytes.length);
        baos.write(brandBytes, 0, brandBytes.length);
        return baos.toByteArray();
    }

    /**
     * Translates {@code &}-prefixed color codes (e.g. {@code &c}) to Minecraft's
     * section-sign format (§c). Unrecognised {@code &X} sequences are left as-is.
     */
    public static String translateColors(String s) {
        if (s == null || s.isEmpty()) return "";
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && COLOR_CHARS.indexOf(chars[i + 1]) != -1) {
                chars[i] = '§';
                i++;
            }
        }
        return new String(chars);
    }

    private static void writeVarInt(ByteArrayOutputStream baos, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            baos.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        baos.write(value);
    }
}
