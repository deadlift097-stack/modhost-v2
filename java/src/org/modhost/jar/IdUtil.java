package org.modhost.jar;

/** Small helpers shared by the inspector and the host for turning free text into safe ids. */
public final class IdUtil {
    private IdUtil() {}

    /** Lowercase ASCII letters, digits, '_', '-' and '.'; everything else becomes '_'. Never empty. */
    public static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                b.append(Character.toLowerCase(c));
            } else if (c == '_' || c == '-' || c == '.') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        return b.length() == 0 ? "mod" : b.toString();
    }

    /** "my_mod-1.2" to "My Mod 1.2": a readable display name made from an id. */
    public static String prettyName(String id) {
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '-') {
                b.append(' ');
                up = true;
            } else if (up && Character.isLetter(c)) {
                b.append(Character.toUpperCase(c));
                up = false;
            } else {
                b.append(c);
                up = false;
            }
        }
        return b.toString().trim();
    }
}
