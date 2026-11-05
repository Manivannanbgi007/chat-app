package com.chatapp.media.util;

import java.util.Locale;

/**
 * File names come from the user and are shown to other people, so they are cleaned:
 * only the last part of a path, no control or invisible characters (for example the "right-to-left override"
 * that makes "photo-gpj.exe" look like "photo-exe.jpg"), no characters that are special in file systems,
 * at most 150 characters (the extension is kept). The name is NEVER used as a path or a storage key.
 */
public final class FileNameCleaner {

    public static final int MAX_LENGTH = 150;
    public static final String FALLBACK = "file";

    private static final int MAX_EXTENSION_LENGTH = 10;

    private FileNameCleaner() {
    }

    public static String clean(String original) {
        if (original == null) {
            return FALLBACK;
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);

        StringBuilder cleaned = new StringBuilder();
        name.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .filter(cp -> Character.getType(cp) != Character.FORMAT)          // invisible and direction characters
                .filter(cp -> "<>:\"|?*".indexOf(cp) < 0)
                .forEach(cleaned::appendCodePoint);
        name = cleaned.toString().strip();

        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return FALLBACK;
        }
        return truncate(name);
    }

    /** The extension in lower case without the dot, or "" when there is none. */
    public static String extension(String cleanName) {
        int dot = cleanName.lastIndexOf('.');
        if (dot < 0 || dot == cleanName.length() - 1) {
            return "";
        }
        return cleanName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Cuts the name to 150 characters but keeps a short extension. */
    private static String truncate(String name) {
        if (name.codePointCount(0, name.length()) <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot - 1 <= MAX_EXTENSION_LENGTH) ? name.substring(dot) : "";
        String base = extension.isEmpty() ? name : name.substring(0, dot);
        int baseLength = MAX_LENGTH - extension.codePointCount(0, extension.length());
        int end = base.offsetByCodePoints(0, Math.min(baseLength, base.codePointCount(0, base.length())));
        return base.substring(0, end) + extension;
    }
}
