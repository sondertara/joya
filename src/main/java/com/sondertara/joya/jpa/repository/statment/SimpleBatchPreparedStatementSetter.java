package com.sondertara.joya.jpa.repository.statment;

import com.sondertara.joya.core.model.TableEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.lang.NonNull;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * @author huangxiaohu
 */
public class SimpleBatchPreparedStatementSetter implements BatchPreparedStatementSetter {


    private final List<TableEntity> rows;

    public SimpleBatchPreparedStatementSetter(List<TableEntity> rows) {
        this.rows = rows;
    }

    @Override
    public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
        TableEntity table = rows.get(i);
        Map<String, Object> data = table.getData();
        Map<String, SqlParameterValue> sqlType = table.getSqlType();
        Map<String, Class<?>> columnType = table.getColumnType();
        int colIndex = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            colIndex++;
            //if (sqlType.containsKey(entry.getKey())) {
            //    value = sqlType.get(entry.getKey());
            //}
            int type = StatementCreatorUtils.javaTypeToSqlParameterType(columnType.get(entry.getKey()));

            SqlParameterValue paramValue = new SqlParameterValue(type, value);
            StatementCreatorUtils.setParameterValue(ps, colIndex, paramValue, paramValue.getValue());
            //} else {
            //    StatementCreatorUtils.setParameterValue(ps, colIndex, SqlTypeValue.TYPE_UNKNOWN, value);
            //}
        }
    }

    @Override
    public int getBatchSize() {
        return this.rows.size();
    }
}
