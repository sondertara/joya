package com.sondertara.joya.exceptions;

import com.sondertara.common.exception.TaraException;

/**
 * @author huangxiaohu
 */
public class IllegalSqlException extends TaraException {

    public IllegalSqlException(String format, Object... arguments) {
        super(format, arguments);
    }
}
