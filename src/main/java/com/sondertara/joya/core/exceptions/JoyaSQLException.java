package com.sondertara.joya.core.exceptions;

import org.slf4j.helpers.MessageFormatter;

/**
 * @author huangxiaohu
 */
public class JoyaSQLException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JoyaSQLException(String msg) {
        super(msg);
    }

    public JoyaSQLException(String format, Object... arguments) {
        super(MessageFormatter.arrayFormat(format, arguments).getMessage());
    }

    public JoyaSQLException(Throwable cause, String format, Object... arguments) {
        super(MessageFormatter.arrayFormat(format, arguments).getMessage(), cause);
    }

    public JoyaSQLException(Throwable cause) {
        super(cause);
    }
}
