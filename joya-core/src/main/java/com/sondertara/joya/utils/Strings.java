package com.sondertara.joya.utils;

/**
 * 字符串工具，提供 joya-core 所需的字符串判空、切分、命名风格转换与格式化操作。
 *
 * @author huangxiaohu
 */
public final class Strings {

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

    public static boolean hasLength(CharSequence cs) {
        return cs != null && cs.length() > 0;
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

    public static String toUnderlineCase(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        int len = str.length();
        StringBuilder sb = new StringBuilder(len + 4);
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = str.charAt(i - 1);
                    if (prev != '_' && prev != '.' && !Character.isWhitespace(prev)) {
                        boolean prevLowerOrDigit = Character.isLowerCase(prev) || Character.isDigit(prev);
                        boolean nextLower = (i + 1 < len) && Character.isLowerCase(str.charAt(i + 1));
                        if (prevLowerOrDigit || (Character.isUpperCase(prev) && nextLower)) {
                            sb.append('_');
                        }
                    }
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
}
