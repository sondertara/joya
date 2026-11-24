package com.sondertara.joya.exceptions;

import java.io.Serial;
import java.util.Arrays;

/**
 * Joya 数据访问与框架运行时根异常（零外部依赖，内置轻量 {@code {}} 占位符格式化）。
 *
 * @author huangxiaohu
 */
public class DbException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DbException(String msg) {
        super(msg);
    }

    public DbException(String format, Object... arguments) {
        super(arrayFormat(format, arguments), getThrowableCandidate(arguments));
    }

    public DbException(Throwable cause) {
        super(cause);
    }

    public DbException(String msg, Throwable cause) {
        super(msg, cause);
    }

    private static String arrayFormat(String format, Object[] argArray) {
        Throwable candidate = getThrowableCandidate(argArray);
        if (candidate == null) {
            return format(format, argArray);
        }
        return format(format, Arrays.copyOf(argArray, argArray.length - 1));
    }

    private static String format(String template, Object[] args) {
        if (template == null || args == null || args.length == 0) {
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

    private static Throwable getThrowableCandidate(Object[] argArray) {
        if (argArray != null && argArray.length != 0) {
            Object lastEntry = argArray[argArray.length - 1];
            return lastEntry instanceof Throwable t ? t : null;
        }
        return null;
    }
}
