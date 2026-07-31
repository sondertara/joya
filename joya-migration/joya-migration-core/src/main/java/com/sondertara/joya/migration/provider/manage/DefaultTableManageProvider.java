
package com.sondertara.joya.migration.provider.manage;



import com.sondertara.joya.migration.provider.AbstractCommonProvider;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
public class DefaultTableManageProvider
    extends AbstractCommonProvider
    implements TableManageProvider {

  public DefaultTableManageProvider(ProductFactoryProvider factoryProvider) {
    super(factoryProvider);
  }

  protected final int executeSql(String sql) {
    if (log.isDebugEnabled()) {
      log.debug("Execute sql :{}", sql);
    }
    try (Connection connection = getDataSource().getConnection();
        Statement st = connection.createStatement()) {
      return st.executeUpdate(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void truncateTableData(String schemaName, String tableName) {
    String fullTableName = factoryProvider.getSqlDialect().tableName(false,null,schemaName, tableName);
    String sql = String.format("TRUNCATE TABLE %s ", fullTableName);
    this.executeSql(sql);
  }

  @Override
  public void dropTable(String schemaName, String tableName) {
    String fullTableName = factoryProvider.getSqlDialect().tableName(false,null,schemaName, tableName);
    String sql = String.format("DROP TABLE %s ", fullTableName);
    this.executeSql(sql);
  }

}
