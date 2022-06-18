package com.sondertara.joya.core.jdbc;

import org.springframework.lang.Nullable;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * @param <T> the type result
 * @author SonderTara
 */
@FunctionalInterface
public interface ConnectionCallback<T> {

  /**
   * do in transaction
   *
   * @param conn sql Connection
   * @return result
   * @throws SQLException e
   */
  @Nullable
  T doInTransaction(Connection conn) throws SQLException;
}
