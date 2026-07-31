package com.sondertara.joya.spring.jpa.repository.statment;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.lang.NonNull;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author huangxiaohu
 */
public class SimpleBatchPreparedStatementSetter implements BatchPreparedStatementSetter {


    private final List<TableMetaContext> rows;

    public SimpleBatchPreparedStatementSetter(List<TableMetaContext> rows) {
        this.rows = rows;
    }

    @Override
    public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
        TableMetaContext table = rows.get(i);
        Map<String, Object> data;
        Map<String, Object> map = table.getData();
        if (CollectionUtils.isEmpty(table.getPreparedParameterNames())) {
            data = table.getData();
        } else {
            data = new LinkedHashMap<>();
            for (String name : table.getPreparedParameterNames()) {
                data.put(name, map.get(name));
            }
        }
        EntityTableMetaDefinition metaDefinition = table.getMetaDefinition();
        int colIndex = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            colIndex++;

            int type = StatementCreatorUtils.javaTypeToSqlParameterType(metaDefinition.findFieldByColumn(entry.getKey()).getType());

            SqlParameterValue paramValue = new SqlParameterValue(type, value);
            StatementCreatorUtils.setParameterValue(ps, colIndex, paramValue, paramValue.getValue());
        }
    }

    @Override
    public int getBatchSize() {
        return this.rows.size();
    }
}
