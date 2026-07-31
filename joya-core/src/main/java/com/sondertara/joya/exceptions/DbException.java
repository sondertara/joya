package com.sondertara.joya.exceptions;


/**
 * 数据库异常基类
 *
 * @author huangxiaohu
 */
public class DbException extends TaraException {
    public DbException(String msg) {
        super(msg);
    }

    public DbException(String format, Object... arguments) {
        super(format, arguments);
    }

    public DbException(Throwable cause) {
        super(cause);
    }

    public DbException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
