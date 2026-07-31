package com.sondertara.joya.migration;

import com.sondertara.joya.core.jdbc.support.DbType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Properties;

public class SourceProperties {

  private DbType productType;

  private String driverClass;
  private String jdbcUrl;
  private String username;
  private String password;

  private String schemaName;
  private String tableName;
  @Getter
  @Setter
  private Properties dbProperties;
  private List<String> columnNames;
  private List<String> distributedKeys;

  public List<String> getDistributedKeys() {
    return distributedKeys;
  }

  public void setDistributedKeys(List<String> distributedKeys) {
    this.distributedKeys = distributedKeys;
  }

  public DbType getProductType() {
    return productType;
  }

  public void setProductType(DbType productType) {
    this.productType = productType;
  }

  public String getDriverClass() {
    return driverClass;
  }

  public void setDriverClass(String driverClass) {
    this.driverClass = driverClass;
  }

  public String getJdbcUrl() {
    return jdbcUrl;
  }

  public void setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public void setSchemaName(String schemaName) {
    this.schemaName = schemaName;
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName;
  }

  public List<String> getColumnNames() {
    return columnNames;
  }

  public void setColumnNames(List<String> columnNames) {
    this.columnNames = columnNames;
  }
}
