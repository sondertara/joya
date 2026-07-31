package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class UpdateBatchByIdSqlCreator extends AbstractSqlCreator {

    private final boolean ignoreNull;

    public UpdateBatchByIdSqlCreator(TableMetaContext tableMetaContext, boolean ignoreNull) {
        super(tableMetaContext);
        this.ignoreNull = ignoreNull;
    }

    public UpdateBatchByIdSqlCreator(TableMetaContext tableMetaContext) {
        super(tableMetaContext);
        this.ignoreNull = false;
    }

    @Override
    protected String createInternal() {
        if (CollectionUtils.isEmpty(this.tableMetaContext.getMetaDefinition().getColumnNames())) {
            throw new IllegalStateException("columns must not be empty");
        }
        StringBuilder updateStatement = new StringBuilder();
        updateStatement.append("UPDATE ");
        updateStatement.append(getTableName());
        updateStatement.append(" SET ");
        StringJoiner sj = new StringJoiner(", ");
        int index = 1;
        for (String columnName : lookColumnNames()) {
            if (columnName.equals(this.tableMetaContext.getPrimaryKeyName())) {
                continue;
            }
            sj.add(columnName + " = ?");
            this.parameters.add(PreparedParameterBinder.builder().position(index++).value(this.tableMetaContext.getColumnValue(columnName)).name(columnName).build());
        }

        updateStatement.append(sj);

        updateStatement.append(" WHERE ").append(this.tableMetaContext.getPrimaryKeyName()).append(" = ?");
        this.parameters.add(PreparedParameterBinder.builder().position(index).value(this.tableMetaContext.getPrimaryKeyValue()).name(this.tableMetaContext.getPrimaryKeyName()).build());

        return updateStatement.toString();
    }


    @Override
    protected List<String> lookColumnNames() {
        List<String> columns = new ArrayList<>();
        if (ignoreNull) {
            for (Map.Entry<String, Object> entry : this.tableMetaContext.getData().entrySet()) {
                if (entry.getKey().equals(this.tableMetaContext.getPrimaryKeyName())) {
                    continue;
                }
                if (entry.getValue() != null) {
                    columns.add(entry.getKey());
                }
            }
        } else {
            for (String name : this.tableMetaContext.getMetaDefinition().getColumnNames()) {
                if (name.equals(this.tableMetaContext.getPrimaryKeyName())) {
                    continue;
                }
                columns.add(name);
            }
        }
        return columns;
    }

}
