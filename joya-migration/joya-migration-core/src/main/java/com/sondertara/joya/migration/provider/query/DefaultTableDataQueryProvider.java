
package com.sondertara.joya.migration.provider.query;


import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.math.HexUtils;
import com.sondertara.joya.core.jdbc.support.meta.ResultSetWrapper;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableData;
import com.sondertara.joya.migration.features.ProductFeatures;
import com.sondertara.joya.migration.provider.AbstractCommonProvider;
import com.sondertara.joya.migration.utils.ObjectCastUtils;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.utils.SqlDataUtils;
import lombok.extern.slf4j.Slf4j;


import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class DefaultTableDataQueryProvider
    extends AbstractCommonProvider
    implements TableDataQueryProvider {

  private final int resultSetType = ResultSet.TYPE_FORWARD_ONLY;
  private final int concurrency = ResultSet.CONCUR_READ_ONLY;

  private int fetchSize = 200;

  public DefaultTableDataQueryProvider(ProductFactoryProvider factoryProvider) {
    super(factoryProvider);
  }

  @Override
  public int getQueryFetchSize() {
    return this.fetchSize;
  }

  @Override
  public void setQueryFetchSize(int size) {
    if (size < 100) {
      throw new IllegalArgumentException(
          "设置的批量处理行数的大小fetchSize=" + size + "不得小于" + 100);
    }
    this.fetchSize = size;
  }

  @Override
  public ResultSetWrapper queryTableData(String schemaName, String tableName, Collection<String> fields,
                                         Collection<String> orders) {
    StringBuilder sb = new StringBuilder("SELECT ");
    sb.append(fields.stream().map(s->sqlDialect.fmtName(false,s)).collect(Collectors.joining(",")));
    sb.append(" FROM ");
    sb.append(sqlDialect.tableName(false,null,schemaName, tableName));
    if (CollectionUtils.isNotEmpty(orders)) {
      sb.append(" ORDER BY ");
      sb.append(orders.stream().map(s->sqlDialect.fmtName(false,s)).collect(Collectors.joining(",")));
    }
    ProductFeatures features = getProductFeatures();
    return this.selectTableData(sb.toString(), features.convertFetchSize(this.fetchSize));
  }

  protected ResultSetWrapper selectTableData(String sql, int fetchSize) {
    if (log.isDebugEnabled()) {
      log.debug("Query table data sql :{}", sql);
    }

    try {
      Connection connection = getDataSource().getConnection();
      Statement statement = connection.createStatement(resultSetType, concurrency);
      statement.setQueryTimeout(3600);
      statement.setFetchSize(fetchSize);
      return ResultSetWrapper.builder()
          .connection(connection)
          .statement(statement)
          .resultSet(statement.executeQuery(sql))
          .build();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  public SchemaTableData queryTableData(Connection connection, String schemaName, String tableName,
                                        int rowCount) {
    String fullTableName = sqlDialect.tableName(false,null,schemaName, tableName);
    String querySQL = String.format("SELECT * FROM %s ", fullTableName);
    SchemaTableData data = new SchemaTableData();
    data.setSchemaName(schemaName);
    data.setTableName(tableName);
    data.setColumns(new ArrayList<>());
    data.setRows(new ArrayList<>());
    try (Statement st = connection.createStatement()) {
      beforeExecuteQuery(connection, schemaName, tableName);
      try (ResultSet rs = st.executeQuery(querySQL)) {
        ResultSetMetaData m = rs.getMetaData();
        int count = m.getColumnCount();
        for (int i = 1; i <= count; i++) {
          data.getColumns().add(m.getColumnLabel(i));
        }

        int counter = 0;
        while (rs.next() && counter++ < rowCount) {
          List<Object> row = new ArrayList<>(count);
          for (int i = 1; i <= count; i++) {
            Object value = rs.getObject(i);
            if (value instanceof byte[]) {
              row.add(HexUtils.encodeHexStr((byte[]) value));
            } else if (value instanceof java.sql.Clob) {
              row.add(SqlDataUtils.extractString((Clob) value));
            } else if (value instanceof java.sql.Blob) {
              byte[] bytes = ObjectCastUtils.castToByteArray(value);
              row.add(HexUtils.encodeHexStr(bytes));
            } else {
              row.add(null == value ? null : value.toString());
            }
          }
          data.getRows().add(row);
        }

        return data;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  protected void beforeExecuteQuery(Connection connection, String schema, String table) {
    // nothing except for hive
  }

}
