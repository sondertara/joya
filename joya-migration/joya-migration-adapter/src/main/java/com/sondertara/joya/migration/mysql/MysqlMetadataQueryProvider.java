
package com.sondertara.joya.migration.mysql;

import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.JdbcType;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ColumnMetaData;
import com.sondertara.joya.core.jdbc.support.meta.IndexDescription;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.SourceProperties;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.meta.AbstractMetadataProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


@Slf4j
public class MysqlMetadataQueryProvider extends AbstractMetadataProvider {

    private static final String SHOW_CREATE_TABLE_SQL = "SHOW CREATE TABLE `%s`.`%s` ";
    private static final String SHOW_CREATE_VIEW_SQL = "SHOW CREATE VIEW `%s`.`%s` ";
    private static final String QUERY_TABLE_LIST_SQL =
            "SELECT `TABLE_SCHEMA`,`TABLE_NAME`,`TABLE_TYPE`,`TABLE_COMMENT` "
                    + "FROM `information_schema`.`TABLES` WHERE `TABLE_SCHEMA`= ? ";
    private static final String QUERY_TABLE_METADATA_SQL =
            "SELECT `TABLE_COMMENT`,`TABLE_TYPE` FROM `information_schema`.`TABLES` "
                    + "WHERE `TABLE_SCHEMA` = ? AND `TABLE_NAME` = ?";

    private static final String QUERY_TABLE_PRIMARY_KEYS_SQL =
            "SELECT `COLUMN_NAME` FROM `information_schema`.`COLUMNS` WHERE `TABLE_SCHEMA` = ? AND `TABLE_NAME` = ? AND `COLUMN_KEY` = 'PRI'";


    public MysqlMetadataQueryProvider(ProductFactoryProvider factoryProvider) {
        super(factoryProvider);
    }

    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getCatalogs()) {
            while (rs.next()) {
                Optional.ofNullable(rs.getString(1)).ifPresent(result::add);
            }
            return result.stream().distinct().collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(QUERY_TABLE_LIST_SQL)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchema(rs.getString("TABLE_SCHEMA"));
                    td.setTableName(rs.getString("TABLE_NAME"));
                    td.setRemarks(rs.getString("TABLE_COMMENT"));
                    String tableType = rs.getString("TABLE_TYPE");
                    if (tableType.equalsIgnoreCase("VIEW")) {
                        td.setTableType("VIEW");
                    } else {
                        td.setTableType("TABLE");
                    }

                    result.add(td);
                }

                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public TableDescription queryTableMeta(Connection connection, String schemaName, String tableName) {
        try (PreparedStatement ps = connection.prepareStatement(QUERY_TABLE_METADATA_SQL)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchema(schemaName);
                    td.setTableName(tableName);
                    td.setRemarks(rs.getString(1));

                    String tableType = rs.getString(2);
                    if (tableType.equalsIgnoreCase("VIEW")) {
                        td.setTableType("VIEW");
                    } else {
                        td.setTableType("TABLE");
                    }

                    return td;
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData()
                .getColumns(schemaName, null, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
            return columns.stream().distinct().collect(Collectors.toList());
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
                .getColumns(schemaName, null, tableName, null)) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String remarks = columns.getString("REMARKS");
                String columnDefault = columns.getString("COLUMN_DEF");
                for (ColumnDescription cd : ret) {
                    if (columnName.equals(cd.getColumnName())) {
                        cd.setRemarks(remarks);
                        // 补充默认值信息
                        cd.setDefaultValue(columnDefault);
                    }
                }
            }
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        List<String> ret = new ArrayList<>();
        try (ResultSet primaryKeys = connection.getMetaData()
                .getPrimaryKeys(schemaName, null, tableName)) {
            while (primaryKeys.next()) {
                ret.add(primaryKeys.getString("COLUMN_NAME"));
            }
            return ret.stream().distinct().collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized List<IndexDescription> queryTableIndexes(Connection connection, String schemaName,
                                                                 String tableName) {
        setCatalogName(schemaName);
        return super.queryTableIndexes(connection, schemaName, tableName);
    }

    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute(String.format(SHOW_CREATE_TABLE_SQL, schemaName, tableName))) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(2);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute(String.format(SHOW_CREATE_VIEW_SQL, schemaName, tableName))) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(2);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 0,1", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    @Override
    public void testQuerySQL(Connection connection, String sql) {
        String testQuerySql = String.format("explain %s", sql.replace(";", ""));
        if (log.isDebugEnabled()) {
            log.debug("Execute sql :{}", testQuerySql);
        }
        try (Statement st = connection.createStatement()) {
            st.execute(testQuerySql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
                                     boolean addCr, boolean withRemarks) {

        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        JdbcType type = v.getType();

        String retval = "`" + fieldname + "`  ";

        switch (type) {
            case TIMESTAMP:
            case TIMESTAMP_WITH_TIMEZONE:
                retval += "DATETIME";
                break;
            case TIME:
            case TIME_WITH_TIMEZONE:
                retval += "TIME";
                break;
            case DATE:
                retval += "DATE";
                break;
            case TINYINT:
                retval += "TINYINT";
                break;
            case BOOLEAN:
                retval += "BOOL";
                break;
            case BIT:
                retval += "BIT";
                break;
            case INTEGER:
                retval += "INT";
                break;
            case BIGINT:
            case DECIMAL:
            case DOUBLE:
            case NUMERIC:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "BIGINT AUTO_INCREMENT NOT NULL";
                    } else {
                        retval += "BIGINT NOT NULL";
                    }
                } else {
                    // Integer values...
                    if (precision == 0) {
                        if (length > 9) {
                            if (length < 19) {
                                // can hold signed values between -9223372036854775808 and 9223372036854775807
                                // 18 significant digits
                                retval += "BIGINT";
                            } else {
                                retval += "DECIMAL(" + length + ")";
                            }
                        } else {
                            retval += "INT";
                        }
                    } else {
                        // Floating point values...
                        if (length > 15) {
                            retval += "DECIMAL(" + length;
                            if (precision > 0) {
                                retval += ", " + precision;
                            }
                            retval += ")";
                        } else {
                            // A double-precision floating-point number is accurate to approximately 15
                            // decimal places.
                            // http://mysql.mirrors-r-us.net/doc/refman/5.1/en/numeric-type-overview.html
                            retval += "DOUBLE";
                        }
                    }
                }
                break;
            case CHAR:
            case NCHAR:
                retval += "CHAR(" + length + ")";
                break;
            case VARCHAR:
            case NVARCHAR:
                retval += "VARCHAR(" + length + ")";
                break;
            case CLOB:
            case LONGNVARCHAR:
            case LONGVARCHAR:
                if (length > 0) {
                    if (length == 1) {
                        retval += "CHAR(1)";
                    } else if (length < 256) {
                        retval += "VARCHAR(" + length + ")";
                    } else if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                        /*
                         * MySQL5.6中varchar字段为主键时最大长度为254,例如如下的建表语句在MySQL5.7下能通过，但在MySQL5.6下无法通过：
                         *	create table `t_test`(
                         *	`key` varchar(1024) binary,
                         *	`val` varchar(1024) binary,
                         *	primary key(`key`)
                         * );
                         */
                        retval += "VARCHAR(254) BINARY";
                    } else if (length < 65536) {
                        retval += "TEXT";
                    } else if (length < 16777216) {
                        retval += "MEDIUMTEXT";
                    } else {
                        retval += "LONGTEXT";
                    }
                } else {
                    retval += "TINYTEXT";
                }
                break;
            case BINARY:
                retval += "LONGBLOB";
                break;
            default:
                retval += " LONGTEXT";
                break;
        }

        if (withRemarks && StringUtils.isNotBlank(v.getRemarks())) {
            retval += String.format(" COMMENT '%s' ", v.getRemarks().replace("'", "\\'"));
        }

        if (addCr) {
            retval += NEWLINE;
        }

        return retval;
    }

    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    @Override
    public void preAppendCreateTableSql(StringBuilder builder) {
        // builder.append( Const.IF_NOT_EXISTS );
    }

    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
                                         SourceProperties tblProperties) {
        builder.append("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        if (StringUtils.isNotBlank(tblComment)) {
            builder.append(String.format(" COMMENT='%s' ", tblComment.replace("'", "\\'")));
        }
    }
}