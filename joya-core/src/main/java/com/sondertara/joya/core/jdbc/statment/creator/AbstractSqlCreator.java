package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author huangxiaohu
 */
public abstract class AbstractSqlCreator implements SqlCreator {
    protected final TableMetaContext tableMetaContext;

    protected final List<PreparedParameterBinder> parameters;

    protected AbstractSqlCreator(TableMetaContext tableMetaContext) {
        this.tableMetaContext = tableMetaContext;
        if (tableMetaContext.isEntityListener()) {
            //refresh the data map
            this.tableMetaContext.initData();
        }
        this.parameters = new ArrayList<>();
    }

    @Override
    public List<PreparedParameterBinder> parameters() {
        return this.parameters;
    }

    @Override
    public String create() {
        String internal = createInternal();
        if (null != this.parameters) {
            this.parameters.sort(Comparator.comparingInt(PreparedParameterBinder::getPosition));
        }
        return internal;
    }


    public static List<PreparedParameterBinder> getPreparedParameterBinders(List<Pair<String, Object>> params, List<PreparedParameterBinder> list) {
        for (int i = 0; i < params.size(); i++) {
            Pair<String, Object> pair = params.get(i);
            list.add(PreparedParameterBinder.builder().position(i + 1).name(pair.getKey()).value(pair.getValue()).build());
        }
        return list;
    }

    protected List<String> lookColumnNames() {
        return new ArrayList<>(this.tableMetaContext.getMetaDefinition().getColumnNames());
    }

    protected abstract String createInternal();

    public String getTableName() {
        return this.tableMetaContext.getTableName();
    }
}
