package com.sondertara.joya.migration.exceptions;

import com.sondertara.common.exception.TaraException;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/29 16:00
 */
public class ExchangeException extends TaraException {
    public ExchangeException(String format, Object... arguments) {
        super(format, arguments);
    }

    public ExchangeException(int code, String format, Object... arguments) {
        super(code, format, arguments);
    }

    public ExchangeException(String msg, Throwable e) {
        super(msg, e);
    }

    public ExchangeException(Throwable cause) {
        super(cause);
    }
}
