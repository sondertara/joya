
package com.sondertara.joya.migration.provider.meta;


import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ColumnMetaData;
import com.sondertara.joya.core.jdbc.support.meta.IndexDescription;
import com.sondertara.joya.core.jdbc.support.meta.IndexFieldMeta;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.core.jdbc.support.meta.TableIndexEnum;
import com.sondertara.joya.migration.SourceProperties;
import com.sondertara.joya.migration.provider.AbstractCommonProvider;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 数据库元信息抽象基类
 *
 * @author tang
 */
@Slf4j
public abstract class AbstractMetadataProvider
    extends AbstractCommonProvider
    implements MetadataProvider {

  protected static final String[] TABLE_TYPES = new String[]{"TABLE", "VIEW", "PARTITIONED TABLE"};

  protected String catalogName = null;

  protected AbstractMetadataProvider(ProductFactoryProvider factoryProvider) {
    super(factoryProvider);
  }

  public void setCatalogName(String catalogName) {
    this.catalogName = catalogName;
  }

  @Override
  public List<String> querySchemaList(Connection connection) {
    Set<String> ret = new LinkedHashSet<>();
    try (ResultSet schemas = connection.getMetaData().getSchemas()) {
      while (schemas.next()) {
        ret.add(schemas.getString("TABLE_SCHEM"));
      }
      return new ArrayList<>(ret);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<TableDescription> queryTableList(Connection connection, String schemaName) {
    List<TableDescription> ret = new ArrayList<>();
    Set<String> uniqueSet = new LinkedHashSet<>();
    try (ResultSet tables = connection.getMetaData()
        .getTables(catalogName, schemaName, "%", TABLE_TYPES)) {
      while (tables.next()) {
        String tableName = tables.getString("TABLE_NAME");
        if (uniqueSet.contains(tableName)) {
          continue;
        } else {
          uniqueSet.add(tableName);
        }

        TableDescription td = new TableDescription();
        td.setSchema(schemaName);
        td.setTableName(tableName);
        td.setRemarks(tables.getString("REMARKS"));
        td.setTableType(tables.getString("TABLE_TYPE").toUpperCase());
        ret.add(td);
      }
      return ret;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public TableDescription queryTableMeta(Connection connection, String schemaName,
      String tableName) {
    try (ResultSet tables = connection.getMetaData()
        .getTables(catalogName, schemaName, tableName, TABLE_TYPES)) {
      if (tables.next()) {
        TableDescription td = new TableDescription();
        td.setSchema(schemaName);
        td.setTableName(tableName);
        td.setRemarks(tables.getString("REMARKS"));
        td.setTableType(tables.getString("TABLE_TYPE").toUpperCase());
        return td;
      }
      return null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<String> queryTableColumnName(Connection connection, String schemaName,
      String tableName) {
    Set<String> columns = new LinkedHashSet<>();
    try (ResultSet rs = connection.getMetaData()
        .getColumns(catalogName, schemaName, tableName, null)) {
      while (rs.next()) {
        columns.add(rs.getString("COLUMN_NAME"));
      }
      return new ArrayList<>(columns);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
                                                      String tableName) {
    String sql = this.getTableFieldsQuerySQL(schemaName, tableName);
    List<ColumnDescription> ret = this.querySelectSqlColumnMeta(connection, sql);

    // 补充一下注释信息
    try (ResultSet columns = connection.getMetaData()
        .getColumns(catalogName, schemaName, tableName, null)) {
      while (columns.next()) {
        String columnName = columns.getString("COLUMN_NAME");
        String remarks = columns.getString("REMARKS");
        for (ColumnDescription cd : ret) {
          if (columnName.equals(cd.getColumnName())) {
            cd.setRemarks(remarks);
          }
        }
      }
      return ret;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
      String tableName) {
    Set<String> ret = new LinkedHashSet<>();
    try (ResultSet primaryKeys = connection.getMetaData()
        .getPrimaryKeys(catalogName, schemaName, tableName)) {
      while (primaryKeys.next()) {
        ret.add(primaryKeys.getString("COLUMN_NAME"));
      }
      return new ArrayList<>(ret);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<IndexDescription> queryTableIndexes(Connection connection, String schemaName, String tableName) {
    List<IndexDescription> ret = new ArrayList<>();
    Map<String, List<IndexFieldMeta>> indexFieldsMap = new HashMap<>();
    Map<String, TableIndexEnum> indexTypesMap = new HashMap<>();
    try (ResultSet rs = connection.getMetaData()
        .getIndexInfo(catalogName, schemaName, tableName, false, true)) {
      while (rs.next()) {
        String indexName = rs.getString("INDEX_NAME");
        Boolean nonUnique = rs.getBoolean("NON_UNIQUE");
        String orderMethod = rs.getString("ASC_OR_DESC");
        String columnName = rs.getString("COLUMN_NAME");
        Integer ordinalPosition = rs.getInt("ORDINAL_POSITION");

        Boolean isAscOrder = Objects.isNull(orderMethod) ? null
            : ("A".equals(orderMethod) ? true : "D".equals(orderMethod) ? false : null);
        IndexFieldMeta indexFieldMeta = new IndexFieldMeta(columnName, ordinalPosition, isAscOrder);
        if (StringUtils.isNotBlank(indexName)) {
          indexTypesMap.putIfAbsent(indexName,
              (null != nonUnique)
                  ? (nonUnique ? TableIndexEnum.NORMAL : TableIndexEnum.UNIQUE)
                  : TableIndexEnum.NORMAL
          );
          indexFieldsMap.computeIfAbsent(indexName, key -> new ArrayList<>()).add(indexFieldMeta);
        }
      }
      if (!indexTypesMap.isEmpty()) {
        List<String> primaryKeys = queryTablePrimaryKeys(connection, schemaName, tableName);
        for (Map.Entry<String, TableIndexEnum> entry : indexTypesMap.entrySet()) {
          String indexName = entry.getKey();
          TableIndexEnum typeEnum = entry.getValue();
          List<IndexFieldMeta> indexFields = indexFieldsMap.get(indexName);
          if (CollectionUtils.isNotEmpty(indexFields)) {
            List<String> fieldList = indexFields.stream()
                .map(IndexFieldMeta::getFieldName)
                .collect(Collectors.toList());
            if (primaryKeys.size() == indexFields.size() && primaryKeys.containsAll(fieldList)) {
              continue;
            }
            ret.add(new IndexDescription(typeEnum, indexName, indexFields));
          }
        }
      }
      return ret;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
    return factoryProvider.getSqlDialect().tableName(false,null,schemaName, tableName);
  }

  @Override
  public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
                                   boolean addCr, boolean withRemarks) {
    throw new RuntimeException("AbstractDatabase Unimplemented!");
  }

  @Override
  public void preAppendCreateTableSql(StringBuilder builder) {
    // NOTHING, Please override by subclass!
  }

  @Override
  public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
    // 不支持主键的数据库类型(例如：hive)，需要覆盖掉该方法
    if (CollectionUtils.isNotEmpty(primaryKeys)) {
      String primaryKeyAsString = getPrimaryKeyAsString(primaryKeys);
      builder.append(", PRIMARY KEY (").append(primaryKeyAsString).append(")");
    }
  }

  @Override
  public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
      SourceProperties tblProperties) {
    // Nothing, please override by subclass!
  }

  @Override
  public String getPrimaryKeyAsString(List<String> pks) {
    if (!pks.isEmpty()) {
      return

              pks.stream().map(s->
              factoryProvider.getSqlDialect().fmtName(false,s)).collect(Collectors.joining(StringUtils.COMMA));
    }
    return StringUtils.EMPTY;
  }

  @Override
  public List<String> getTableColumnCommentDefinition(TableDescription td,
      List<ColumnDescription> cds) {
    throw new RuntimeException("AbstractDatabase Unimplemented!");
  }

  /**
   * 执行写SQL操作
   *
   * @param sql 写SQL语句
   */
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

  protected ColumnDescription buildColumnDescription(ResultSetMetaData m, int i) throws SQLException {
    String name = StringUtils.defaultString(m.getColumnLabel(i), m.getColumnName(i));
    ColumnDescription cd = new ColumnDescription();
    cd.setColumnName(name);
    cd.setLabelName(name);
    cd.setColumnType(m.getColumnType(i));
    if (0 != cd.getColumnType()) {
      cd.setColumnTypeName(m.getColumnTypeName(i));
      cd.setColumnClassName(m.getColumnClassName(i));
      cd.setDisplaySize(m.getColumnDisplaySize(i));
      cd.setPrecisionSize(m.getPrecision(i));
      cd.setScaleSize(m.getScale(i));
      cd.setAutoIncrement(m.isAutoIncrement(i));
      cd.setNullable(m.isNullable(i) != ResultSetMetaData.columnNoNulls);
    } else {
      // 处理视图中NULL as fieldName的情况
      cd.setColumnTypeName("CHAR");
      cd.setColumnClassName(String.class.getName());
      cd.setDisplaySize(1);
      cd.setPrecisionSize(1);
      cd.setScaleSize(0);
      cd.setAutoIncrement(false);
      cd.setNullable(true);
    }

    boolean signed = false;
    try {
      signed = m.isSigned(i);
    } catch (Exception ignored) {
      // This JDBC Driver doesn't support the isSigned method
      // nothing more we can do here by catch the exception.
    }
    cd.setSigned(signed);
    return cd;
  }

  /**************************************
   * internal function
   **************************************/
  protected List<ColumnDescription> getSelectSqlColumnMeta(Connection connection, String querySQL) {
    return getSelectSqlColumnMeta(connection, querySQL, conn -> {});
  }

  protected List<ColumnDescription> getSelectSqlColumnMeta(Connection connection, String querySQL,
                                                           Consumer<Connection> preQueryFunc) {
    List<ColumnDescription> ret = new ArrayList<>();
    try (Statement st = connection.createStatement()) {
      preQueryFunc.accept(connection);
      try (ResultSet rs = st.executeQuery(querySQL)) {
        ResultSetMetaData m = rs.getMetaData();
        int columns = m.getColumnCount();
        for (int i = 1; i <= columns; i++) {
          ColumnDescription cd = buildColumnDescription(m, i);
          cd.setDbType(getProductType());
          ret.add(cd);
        }
        return ret;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

}