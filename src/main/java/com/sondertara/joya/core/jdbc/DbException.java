package com.sondertara.joya.core.jdbc;

/**
 * 数据库异常基类
 *
 * @author huangxiaohu
 */
public class DbException extends RuntimeException {
  public DbException(String msg) {
    super(msg);
  }

  public DbException(String msg, Throwable cause) {
    super(msg, cause);
  }
}
