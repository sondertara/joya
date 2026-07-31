package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

/**
 * 根据实体生成sql模板
 *
 * @author huangxiaohu
 */
public class UpdateSqlCreator<T> extends AbstractSqlCreator {
    @Getter
    protected boolean ignoreNull;

    protected WhereCondition<T> whereCriterion;

    public UpdateSqlCreator(TableMetaContext entity) {
        this(entity, false);

    }

    public UpdateSqlCreator(TableMetaContext entity, boolean ignoreNull) {
        super(entity);
        this.ignoreNull = ignoreNull;

    }

    public void setWhereCondition(WhereCondition<T> whereCriterion) {
        this.whereCriterion = whereCriterion;
    }

    public UpdateSqlCreator<T> where(UnaryOperator<WhereCondition<T>> func) {
        if (null == this.whereCriterion) {
            this.whereCriterion = new WhereCondition<>();
        }
        func.apply(this.whereCriterion);
        return this;
    }

    @Override

    protected List<String> lookColumnNames() {
        List<String> columns = new ArrayList<>();
        if (ignoreNull) {
            for (Map.Entry<String, Object> entry : this.tableMetaContext.getData().entrySet()) {
                if (entry.getValue() != null) {
                    columns.add(entry.getKey());
                }
            }
            return columns;
        }
        columns = new ArrayList<>(this.tableMetaContext.getMetaDefinition().getColumnNames());
        return columns;
    }


    @Override
    protected String createInternal() {

        checkContext();
        StringBuilder updateStatement = new StringBuilder();
        updateStatement.append("UPDATE ");
        updateStatement.append(getTableName());
        updateStatement.append(" SET ");
        StringJoiner sj = new StringJoiner(", ");
        int index = 1;
        for (String columnName : lookColumnNames()) {
            sj.add(columnName + " = ?");
            parameters.add(PreparedParameterBinder.builder().position(index++).value(tableMetaContext.getColumnValue(columnName)).name(columnName).build());
        }

        updateStatement.append(sj);
        if (null != whereCriterion && whereCriterion.getCounts() > 0) {
            updateStatement.append(" WHERE ").append(whereCriterion.getQueryString());
            List<Pair<String, Object>> params = whereCriterion.getParams();
            for (Pair<String, Object> param : params) {
                parameters.add(PreparedParameterBinder.builder().name(param.getKey()).position(index++).value(param.getValue()).build());
            }
        }

        return updateStatement.toString();
    }


    protected void checkContext() {
        if (CollectionUtils.isEmpty(this.tableMetaContext.getMetaDefinition().getColumnNames())) {
            throw new IllegalStateException("Columns must not be empty");
        }

        if (CollectionUtils.isEmpty(this.tableMetaContext.getData())) {
            throw new IllegalStateException("Update columns by data[TableMetaContext#data] must not be empty");
        }
    }

}
