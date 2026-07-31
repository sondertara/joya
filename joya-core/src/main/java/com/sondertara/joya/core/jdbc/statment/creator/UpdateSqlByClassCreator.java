package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;

import java.util.List;

/**
 * 根据类生成sql模板
 *
 * @author huangxiaohu
 */
public class UpdateSqlByClassCreator<T> extends UpdateSqlCreator<T> {
    public UpdateSqlByClassCreator(TableMetaContext entity) {
        super(entity,false);
    }

    @Override
    public List<PreparedParameterBinder> parameters() {
        throw new IllegalStateException("Update SQL is parsed by Class, this method are not supported");
    }

    @Override
    protected void checkContext() {
        if (CollectionUtils.isEmpty(this.tableMetaContext.getMetaDefinition().getColumnNames())) {
            throw new IllegalStateException("Columns must not be empty");
        }
    }
}
