
package com.sondertara.joya.core.jdbc.support.meta;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC连接及结果集实体参数定义类
 *
 * @author tang
 */
@Slf4j
public class ResultSetWrapper implements AutoCloseable {

  private boolean isAutoCommit;
  @Getter
  private Connection connection;
  @Getter
  private Statement statement;
  @Getter
  private ResultSet resultSet;

  @Builder
  public ResultSetWrapper(ResultSet resultSet, Statement statement, Connection connection) {
    this.resultSet = resultSet;
    this.statement = statement;
    this.connection = connection;
    try {
      this.isAutoCommit = connection.getAutoCommit();
    } catch (SQLException e) {
      log.warn("Jdbc Connection getAutoCommit() failed, set default true, error: {}", e.getMessage());
      this.isAutoCommit = false;
    }
  }

  @Override
  public void close() {
    try {
      connection.setAutoCommit(isAutoCommit);
    } catch (SQLException e) {
      log.warn("Jdbc Connection setAutoCommit() failed, error: {}", e.getMessage());
    }
    try {
      resultSet.close();
    } catch (SQLException e) {
      log.warn("Jdbc ResultSet close() failed, error: {}", e.getMessage());
    }
    try {
      statement.close();
    } catch (SQLException e) {
      log.warn("Jdbc Statement close() failed, error: {}", e.getMessage());
    }
    try {
      connection.close();
    } catch (SQLException e) {
      log.warn("Jdbc Connection close() failed, error: {}", e.getMessage());
    }
  }
}
