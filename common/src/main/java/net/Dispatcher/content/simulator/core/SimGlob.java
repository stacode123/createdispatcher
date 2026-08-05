package net.Dispatcher.content.simulator.core;

import java.util.regex.Pattern;

/**
 * Station-name glob matching, mirroring Create's catnip
 * {@code Glob.toRegexPattern(filter, "")} (a port of the JDK glob
 * translator with no separator characters): {@code *} matches any run,
 * {@code ?} any single character, {@code [...]}/{@code [!...]} character
 * classes with ranges, {@code {a,b}} alternation, {@code \} escapes.
 * Whole-string match. A malformed glob (Create would throw a
 * {@code PatternSyntaxException} and break the schedule) falls back to
 * treating everything except {@code *} literally.
 */
public class SimGlob {

    private static final String REGEX_META = ".^$+*?{[]|()}";

    public static Pattern compile(String glob) {
        try {
            return Pattern.compile(toRegex(glob));
        } catch (RuntimeException malformed) {
            return Pattern.compile(starOnlyRegex(glob));
        }
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean inGroup = false;
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i++);
            switch (c) {
                case '\\' -> {
                    if (i == glob.length())
                        throw new IllegalArgumentException("trailing escape");
                    char next = glob.charAt(i++);
                    if (isRegexMeta(next) || next == '\\')
                        regex.append('\\');
                    regex.append(next);
                }
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    regex.append('[');
                    if (i < glob.length() && glob.charAt(i) == '!') {
                        regex.append('^');
                        i++;
                    } else if (i < glob.length() && glob.charAt(i) == '^') {
                        regex.append("\\^");
                        i++;
                    }
                    boolean empty = true;
                    while (i < glob.length() && glob.charAt(i) != ']') {
                        char inner = glob.charAt(i++);
                        if (inner == '\\' || inner == '[' || inner == '&') {
                            regex.append('\\');
                        }
                        regex.append(inner);
                        empty = false;
                    }
                    if (i == glob.length() || empty)
                        throw new IllegalArgumentException("unclosed or empty class");
                    i++;
                    regex.append(']');
                }
                case '{' -> {
                    if (inGroup)
                        throw new IllegalArgumentException("nested group");
                    inGroup = true;
                    regex.append("(?:");
                }
                case '}' -> {
                    if (inGroup) {
                        inGroup = false;
                        regex.append(')');
                    } else {
                        regex.append("\\}");
                    }
                }
                case ',' -> regex.append(inGroup ? "|" : ",");
                default -> {
                    if (isRegexMeta(c))
                        regex.append('\\');
                    regex.append(c);
                }
            }
        }
        if (inGroup)
            throw new IllegalArgumentException("unclosed group");
        return regex.toString();
    }

    private static boolean isRegexMeta(char c) {
        return REGEX_META.indexOf(c) != -1;
    }

    private static String starOnlyRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0)
            regex.append(Pattern.quote(literal.toString()));
        return regex.toString();
    }
}
