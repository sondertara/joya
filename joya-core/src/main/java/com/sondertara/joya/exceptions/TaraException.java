package com.sondertara.joya.exceptions;


import com.sondertara.common.base.StringUtils;

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
            return StringUtils.format(format, argArray);
        }
        return StringUtils.format(format, Arrays.copyOf(argArray, argArray.length - 1));
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
