package com.sondertara.joya.utils;

/**
 * 断言工具，替代对第三方工具库的依赖。
 * 支持模板字符串格式化（{} 占位符）。
 *
 * @author huangxiaohu
 */
public final class Assert {

    private Assert() {
    }

    public static void notNull(Object object) {
        notNull(object, "[Assertion failed] - the object must not be null");
    }

    public static void notNull(Object object, String message) {
        if (object == null) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void notNull(Object object, String message, Object... args) {
        if (object == null) {
            throw new IllegalArgumentException(Strings.format(message, args));
        }
    }

    public static void isTrue(boolean expression) {
        isTrue(expression, "[Assertion failed] - the expression must be true");
    }

    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void isTrue(boolean expression, String message, Object... args) {
        if (!expression) {
            throw new IllegalArgumentException(Strings.format(message, args));
        }
    }

    public static void isFalse(boolean expression) {
        isFalse(expression, "[Assertion failed] - the expression must be false");
    }

    public static void isFalse(boolean expression, String message) {
        if (expression) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void isFalse(boolean expression, String message, Object... args) {
        if (expression) {
            throw new IllegalArgumentException(Strings.format(message, args));
        }
    }

    public static void state(boolean expression, String message) {
        if (!expression) {
            throw new IllegalStateException(message);
        }
    }

    public static void state(boolean expression, String message, Object... args) {
        if (!expression) {
            throw new IllegalStateException(Strings.format(message, args));
        }
    }

    public static void notBlank(String text) {
        notBlank(text, "[Assertion failed] - the text must not be blank");
    }

    public static void notBlank(String text, String message) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void notBlank(String text, String message, Object... args) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(Strings.format(message, args));
        }
    }
}
