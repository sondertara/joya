package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
@Slf4j
public class SelectSqlCreator<T> extends GenericSqlCreator {

    private WhereCondition<T> whereCriterion;
    private String extPart;

    public SelectSqlCreator(TableMetaContext tableMetaContext) {
        super(tableMetaContext);
    }


    public void setWhereCriterion(WhereCondition<T> whereCriterion) {
        this.whereCriterion = whereCriterion;
    }

    public SelectSqlCreator<T> where(UnaryOperator<WhereCondition<T>> func) {
        if (null == this.whereCriterion) {
            this.whereCriterion = new WhereCondition<>();
        }
        func.apply(this.whereCriterion);
        return this;
    }

    public SelectSqlCreator<T> extPart(String extPart) {
        this.extPart = extPart;
        return this;
    }


    @Override
    protected String createInternal() {
        EntityTableMetaDefinition metaDefinition = tableMetaContext.getMetaDefinition();
        StringBuilder insertStatement = new StringBuilder();
        insertStatement.append("SELECT ");

        if (getDeclaredColumns().isEmpty()) {
            insertStatement.append("*");
        } else {
            Set<String> collect = getDeclaredColumns().stream().map(s -> JoyaJdbc.config().isColumnUppercase() ? s.toUpperCase() : s.toLowerCase()).collect(Collectors.toSet());
            insertStatement.append(String.join(", ", collect));
        }

        insertStatement.append(" FROM ");

        insertStatement.append(getTableName());
        if (null != whereCriterion && whereCriterion.hasParams()) {
            insertStatement.append(" WHERE ");
            insertStatement.append(whereCriterion.getQueryString());

            getPreparedParameterBinders(whereCriterion.getParams(),this.parameters);
        }
        if (null != extPart) {
            insertStatement.append(" ").append(extPart);
        }
        return insertStatement.toString();
    }

}
