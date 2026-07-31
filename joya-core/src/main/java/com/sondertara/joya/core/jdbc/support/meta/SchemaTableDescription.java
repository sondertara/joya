
package com.sondertara.joya.core.jdbc.support.meta;

import java.util.List;

public class SchemaTableDescription extends TableDescription {

  private List<String> primaryKeys;
  private String createSql;
  private List<ColumnDescription> columns;
  private List<IndexDescription> indexes;

  public List<String> getPrimaryKeys() {
    return primaryKeys;
  }

  public void setPrimaryKeys(List<String> primaryKeys) {
    this.primaryKeys = primaryKeys;
  }

  public String getCreateSql() {
    return createSql;
  }

  public void setCreateSql(String createSql) {
    this.createSql = createSql;
  }

  public List<ColumnDescription> getColumns() {
    return columns;
  }

  public void setColumns(List<ColumnDescription> columns) {
    this.columns = columns;
  }

  public List<IndexDescription> getIndexes() {
    return indexes;
  }

  public void setIndexes(List<IndexDescription> indexes) {
    this.indexes = indexes;
  }
}
