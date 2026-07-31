package com.sondertara.joya.exceptions;

import com.sondertara.common.exception.TaraException;

public class SqlParseException extends TaraException {
    public SqlParseException(String format, Object... arguments) {
        super(format, arguments);
    }

    public SqlParseException(int code, String format, Object... arguments) {
        super(code, format, arguments);
    }

    public SqlParseException(String msg, Throwable e) {
        super(msg, e);
    }

    public SqlParseException(Throwable cause) {
        super(cause);
    }
}
