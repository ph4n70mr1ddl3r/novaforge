package com.novaforge.metadata;

/** camelCase → snake_case with SQL-safe identifier fallback. */
public final class Snake {

    private Snake() {
    }

    public static String caseName(String camelCase) {
        StringBuilder sb = new StringBuilder();
        for (char c : camelCase.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String name = sb.toString();
        if (name.startsWith("_")) {
            name = name.substring(1);   // JournalEntry → journal_entry, not _journal_entry
        }
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "c" + name;
        }
        return name;
    }
}
