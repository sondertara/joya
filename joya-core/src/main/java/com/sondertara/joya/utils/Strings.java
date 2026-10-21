package com.sondertara.joya.utils;

import java.util.StringJoiner;

/**
 * 字符串工具，提供 joay-core 所需的全部字符串操作。
 *
 * @author huangxiaohu
 */
public final class Strings {

    public static final String EMPTY = "";
    public static final String DOT = ".";
    public static final String COMMA = ",";
    public static final String SPACE = " ";

    private Strings() {
    }

    public static boolean isBlank(CharSequence cs) {
        int strLen;
        if (cs == null || (strLen = cs.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(CharSequence cs) {
        return !isBlank(cs);
    }

    public static boolean isEmpty(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    public static boolean hasText(CharSequence cs) {
        return isNotBlank(cs);
    }

    public static boolean hasLength(CharSequence cs) {
        return cs != null && cs.length() > 0;
    }

    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    public static String defaultString(String str) {
        return str == null ? EMPTY : str;
    }

    public static String defaultString(String str, String defaultStr) {
        return str == null ? defaultStr : str;
    }

    public static String join(CharSequence delimiter, Iterable<?> elements) {
        StringJoiner joiner = new StringJoiner(delimiter);
        for (Object element : elements) {
            joiner.add(String.valueOf(element));
        }
        return joiner.toString();
    }

    public static String substringAfter(String str, String separator) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        if (separator == null) {
            return EMPTY;
        }
        int pos = str.indexOf(separator);
        if (pos < 0) {
            return EMPTY;
        }
        return str.substring(pos + separator.length());
    }

    public static String subAfter(String str, char separator, boolean last) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        int pos = last ? str.lastIndexOf(separator) : str.indexOf(separator);
        if (pos < 0) {
            return str;
        }
        return str.substring(pos + 1);
    }

    public static String subAfter(String str, String separator) {
        return subAfter(str, separator, true);
    }

    public static String subAfter(String str, String separator, boolean last) {
        if (str == null || str.isEmpty() || separator == null || separator.isEmpty()) {
            return str;
        }
        int pos = last ? str.lastIndexOf(separator) : str.indexOf(separator);
        if (pos < 0) {
            return str;
        }
        return str.substring(pos + separator.length());
    }

    public static boolean containsIgnoreCase(CharSequence str, CharSequence searchStr) {
        if (str == null || searchStr == null) {
            return false;
        }
        return str.toString().toLowerCase().contains(searchStr.toString().toLowerCase());
    }

    public static String toUnderlineCase(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String toCamelCase(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        return sb.toString();
    }

    public static String lowerFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        char first = str.charAt(0);
        if (Character.isUpperCase(first)) {
            return Character.toLowerCase(first) + str.substring(1);
        }
        return str;
    }

    public static int countMatches(CharSequence str, CharSequence sub) {
        if (str == null || sub == null || str.length() == 0 || sub.length() == 0) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = str.toString().indexOf(sub.toString(), idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    public static String format(String template, Object... args) {
        if (template == null) {
            return null;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + args.length * 10);
        int argIndex = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean isNotEmpty(CharSequence cs) {
        return !isEmpty(cs);
    }

    public static String[] split(String str, String separator) {
        if (str == null || str.isEmpty()) {
            return new String[0];
        }
        if (separator == null || separator.isEmpty()) {
            return new String[]{str};
        }
        return str.split(java.util.regex.Pattern.quote(separator));
    }
}