package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import lombok.Setter;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
@Setter
public class DeleteSqlCreator<T> extends AbstractSqlCreator {

    protected WhereCondition<T> whereCriterion;

    public DeleteSqlCreator(TableMetaContext tableMetaDefinition) {
        super(tableMetaDefinition);
    }


    public DeleteSqlCreator<T> where(UnaryOperator<WhereCondition<T>> func) {
        if (null == this.whereCriterion) {
            this.whereCriterion = new WhereCondition<>();
        }
        func.apply(this.whereCriterion);
        return this;
    }

    @Override
    protected String createInternal() {
        EntityTableMetaDefinition metaDefinition = this.tableMetaContext.getMetaDefinition();
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ");
        if (null != metaDefinition.getSchemaName()) {
            sb.append(metaDefinition.getSchemaName()).append(getTableName());
        } else {
            sb.append(getTableName());
        }
        if (null != whereCriterion && whereCriterion.getCounts() > 0) {
            sb.append(" WHERE ").append(whereCriterion.getQueryString());
            getPreparedParameterBinders(whereCriterion.getParams(), this.parameters);
        }
        return sb.toString();
    }
}
