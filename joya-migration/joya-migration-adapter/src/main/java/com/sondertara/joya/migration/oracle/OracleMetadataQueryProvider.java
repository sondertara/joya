
package com.sondertara.joya.migration.oracle;

import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.JdbcType;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ColumnMetaData;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.meta.AbstractMetadataProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class OracleMetadataQueryProvider extends AbstractMetadataProvider {

    private static final String SHOW_CREATE_TABLE_SQL =
            "SELECT DBMS_METADATA.GET_DDL('TABLE','%s','%s') FROM DUAL ";
    private static final String SHOW_CREATE_VIEW_SQL =
            "SELECT DBMS_METADATA.GET_DDL('VIEW','%s','%s') FROM DUAL ";

    public OracleMetadataQueryProvider(ProductFactoryProvider factoryProvider) {
        super(factoryProvider);
    }

    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        String sql = String.format("SELECT \"OWNER\",\"TABLE_NAME\",\"TABLE_TYPE\",\"COMMENTS\" "
                + "FROM all_tab_comments where \"OWNER\"='%s'", schemaName);
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();) {
            while (rs.next()) {
                TableDescription td = new TableDescription();
                td.setSchema(rs.getString("OWNER"));
                td.setTableName(rs.getString("TABLE_NAME"));
                td.setRemarks(rs.getString("COMMENTS"));
                String tableType = rs.getString("TABLE_TYPE").trim();
                if (tableType.equalsIgnoreCase("VIEW")) {
                    td.setTableType("VIEW");
                } else {
                    td.setTableType("TABLE");
                }

                ret.add(td);
            }

            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_TABLE_SQL, tableName, schemaName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_VIEW_SQL, tableName, schemaName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
                                              String tableName) {
        // Oracle表的主键可以使用如下命令设置主键是否生效
        // 使主键失效：alter table tableName disable primary key;
        // 使主键恢复：alter table tableName enable primary key;
        Set<String> ret = new HashSet<>();
        String sql = String.format(
                "SELECT col.COLUMN_NAME FROM all_cons_columns col INNER JOIN all_constraints con \n"
                        + "ON col.constraint_name=con.constraint_name AND col.OWNER =con.OWNER  AND col.TABLE_NAME =con.TABLE_NAME \n"
                        + "WHERE con.constraint_type = 'P' and con.STATUS='ENABLED' and con.owner='%s' AND con.table_name='%s'",
                schemaName, tableName);
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
        ) {
            while (rs.next()) {
                ret.add(rs.getString("COLUMN_NAME"));
            }

            return new ArrayList<>(ret);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format("SELECT * from (%s) tmp where ROWNUM<=1 ",
                sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    @Override
    public void testQuerySQL(Connection connection, String sql) {
        String testQuerySql = String.format("explain plan for %s", sql.replace(";", ""));
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

        StringBuilder retval = new StringBuilder(128);
        retval.append(" \"").append(fieldname).append("\"    ");

        JdbcType type = v.getType();
        switch (type) {
            case TIMESTAMP:
            case TIMESTAMP_WITH_TIMEZONE:
            case TIME:
            case TIME_WITH_TIMEZONE:
                retval.append("TIMESTAMP");
                break;
            case DATE:
                retval.append("DATE");
                break;
            case BIT:
            case BOOLEAN:
                retval.append("NUMBER(1)");
                break;
            case NUMERIC:

            case TINYINT:
                if (length == 1) {
                    retval.append("NUMBER(1)");
                } else {
                    retval.append("NUMBER");
                    if (length > 0) {
                        retval.append('(').append(length);

                        if (precision > 0) {
                            retval.append(", ").append(precision);
                        }
                        retval.append(')');
                    }
                }
                break;
            case DECIMAL:
            case DOUBLE:
            case FLOAT:
            case REAL:
            case BIGINT:
                retval.append("NUMBER");
                if (length > 0) {
                    if (length > 38) {
                        length = 38;
                    }
                    retval.append('(').append(length);
                    if (precision > 0) {
                        retval.append(", ").append(precision);
                    }
                    retval.append(')');
                }
                break;
            case INTEGER:
                retval.append("INTEGER");
                break;

            case NCLOB:
                retval.append("NCLOB");
                break;
            case CLOB:
                retval.append("CLOB");
            case NVARCHAR:
                if (length == 1) {
                    retval.append("NVARCHAR2(1)");
                } else if (length > 0 && length < 2000) {
                    // VARCHAR2(size)，size最大值为4000，单位是字节；而NVARCHAR2(size)，size最大值为2000，单位是字符
                    retval.append("NVARCHAR2(").append(length).append(')');
                } else {
                    retval.append("CLOB");// We don't know, so we just use the maximum...
                }
                break;
            case CHAR:
                if (length == 1) {
                    retval.append("CHAR(1)");
                } else if (length > 0 && length < 2000) {
                    // VARCHAR2(size)，size最大值为4000，单位是字节；而NVARCHAR2(size)，size最大值为2000，单位是字符
                    retval.append("CHAR(").append(length).append(')');
                } else {
                    retval.append("CLOB");// We don't know, so we just use the maximum...
                }
                break;
            case VARCHAR:
                if (length == 1) {
                    retval.append("VARCHAR2(1)");
                } else if (length > 0 && length < 4000) {
                    // VARCHAR2(size)，size最大值为4000，单位是字节；而NVARCHAR2(size)，size最大值为2000，单位是字符
                    retval.append("VARCHAR2(").append(length).append(')');
                } else {
                    retval.append("CLOB");
                }
                break;
            case BLOB:
            case BINARY:
                // the BLOB can contain binary data.
                retval.append("BLOB");
                break;
            default:
                retval.append("VARCHAR2(2000)");
                break;
        }

        if (addCr) {
            retval.append(NEWLINE);
        }

        return retval.toString();
    }

    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
                                                        List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add(String
                    .format("COMMENT ON TABLE \"%s\".\"%s\" IS '%s' ",
                            td.getSchema(), td.getTableName(),
                            td.getRemarks().replace("'", "")));
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add(String
                        .format("COMMENT ON COLUMN \"%s\".\"%s\".\"%s\" IS '%s' ",
                                td.getSchema(), td.getTableName(), cd.getColumnName(),
                                cd.getRemarks().replace("'", "")));
            }
        }

        return results;
    }

}
