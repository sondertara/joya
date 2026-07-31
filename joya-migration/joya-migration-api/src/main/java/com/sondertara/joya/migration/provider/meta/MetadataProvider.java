
package com.sondertara.joya.migration.provider.meta;


import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ColumnMetaData;
import com.sondertara.joya.core.jdbc.support.meta.IndexDescription;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.SourceProperties;

import java.sql.Connection;
import java.util.List;

/**
 * 元数据查询
 */

public interface MetadataProvider {

  /**
   * 获取数据库类型
   *
   * @return ProductTypeEnum
   */
  DbType getProductType();

  /**
   * 获取数据库的模式schema列表
   *
   * @param connection JDBC连接
   * @return 模式名列表
   */
  List<String> querySchemaList(Connection connection);

  /**
   * 获取指定模式Schema内的所有表列表
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @return 表及视图名列表
   */
  List<TableDescription> queryTableList(Connection connection, String schemaName);

  /**
   * 精确获取表或视图的元数据
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表或视图名称
   * @return
   */
  TableDescription queryTableMeta(Connection connection, String schemaName, String tableName);

  /**
   * 获取指定物理表的DDL语句
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表名称
   * @return 字段元信息列表
   */
  String getTableDDL(Connection connection, String schemaName, String tableName);

  /**
   * 获取指定视图表的DDL语句
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表或视图名称
   * @return 字段元信息列表
   */
  String getViewDDL(Connection connection, String schemaName, String tableName);

  /**
   * 获取指定模式表的字段列表
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表或视图名称
   * @return 字段元信息列表
   */
  List<String> queryTableColumnName(Connection connection, String schemaName,
      String tableName);

  /**
   * 获取指定模式表的元信息
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表或视图名称
   * @return 字段元信息列表
   */
  List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
                                               String tableName);

  /**
   * 获取指定查询SQL的元信息
   *
   * @param connection JDBC连接
   * @param sql        SQL查询语句
   * @return 字段元信息列表
   */
  List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql);

  /**
   * 获取指定模式表的主键字段列表
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表名称
   * @return 主键字段名称列表
   */
  List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName);

  /**
   * 获取指定模式表的索引列表
   *
   * @param connection JDBC连接
   * @param schemaName 模式名称
   * @param tableName  表名称
   * @return 主键字段名称列表
   */
  List<IndexDescription> queryTableIndexes(Connection connection, String schemaName, String tableName);

  /**
   * 测试查询SQL语句的有效性
   *
   * @param connection JDBC连接
   * @param sql        待验证的SQL语句
   */
  void testQuerySQL(Connection connection, String sql);

  /**
   * 获取数据库的表全名
   *
   * @param schemaName 模式名称
   * @param tableName  表名称
   * @return 表全名
   */
  String getQuotedSchemaTableCombination(String schemaName, String tableName);

  /**
   * 获取字段列的结构定义
   *
   * @param v           值元数据定义
   * @param pks         主键字段名称列表
   * @param addCr       是否结尾换行
   * @param useAutoInc  是否自增
   * @param withRemarks 是否带有注释
   * @return 字段定义字符串
   */
  String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
                            boolean withRemarks);

  /**
   * 前置补充建表SQL
   *
   * @param builder 建表SQL的字符串构造器
   */
  void preAppendCreateTableSql(StringBuilder builder);


  /**
   * 在建表SQl中补充主键
   *
   * @param builder     建表SQL的字符串构造器
   * @param primaryKeys 主键字段列表
   */
  void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys);

  /**
   * 后置补充建表SQL
   *
   * @param builder       建表SQL的字符串构造器
   * @param tblComment    表的注释
   * @param primaryKeys   主键列表
   * @param tblProperties 表的属性
   */
  void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
      SourceProperties tblProperties);

  /**
   * 主键列转换为逗号分隔的字符串
   *
   * @param pks 主键字段列表
   * @return 主键字段拼接串
   */
  String getPrimaryKeyAsString(List<String> pks);

  /**
   * 获取表和字段的注释定义
   *
   * @param td  表信息定义
   * @param cds 列信息定义
   * @return 定义字符串列表
   */
  List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds);

  /**
   * 为hive等定制的获取联邦建表导数SQL列表
   *
   * @param fieldNames    字段结构信息
   * @param primaryKeys   主键字段信息
   * @param schemaName    模式名称
   * @param tableName     表名称
   * @param autoIncr      是否允许主键自增
   * @param tblProperties 表的属性信息
   * @return 建表导数SQL列表
   */
  default List<String> getCreateTableSqlList(List<ColumnDescription> fieldNames, List<String> primaryKeys,
                                             String schemaName, String tableName, String tableRemarks, boolean autoIncr, SourceProperties tblProperties) {
    throw new UnsupportedOperationException("Unsupported function!");
  }
}
