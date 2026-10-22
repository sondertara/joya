package com.sondertara.joya.exceptions;


import java.util.Arrays;

/**
 * @author huangxiaohu
 */
public class TaraException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;

    public TaraException(String format, Object... arguments) {

        super(arrayFormat(format, arguments),
                getThrowableCandidate(arguments));
        this.code = 9999;
    }

    public TaraException(int code, String format, Object... arguments) {

        super(arrayFormat(format, arguments),
                getThrowableCandidate(arguments));
        this.code = code;
    }

    static String arrayFormat(String format, Object[] argArray) {
        Throwable candidate = getThrowableCandidate(argArray);
        if (null == candidate) {
            return format(format, argArray);
        }
        return format(format, Arrays.copyOf(argArray, argArray.length - 1));
    }

    /**
     * 内联的 {@code {}} 占位符替换。
     *
     * <p>异常类必须保持为零依赖叶子（{@code utils} 反过来依赖本包），
     * 因此不引用 {@code utils.Strings}，避免 {@code exceptions ↔ utils} 的循环。
     */
    private static String format(String template, Object[] args) {
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

    static Throwable getThrowableCandidate(Object[] argArray) {
        if (argArray != null && argArray.length != 0) {
            Object lastEntry = argArray[argArray.length - 1];
            return lastEntry instanceof Throwable ? (Throwable) lastEntry : null;
        } else {
            return null;
        }
    }

    public TaraException(String msg, Throwable e) {
        super(msg, e);
        this.code = 9999;
    }

    public TaraException(Throwable cause) {
        super(cause);
        this.code = 9999;
    }

    public String getCode() {
        return String.format("TE%010d\n", code);
    }

}
