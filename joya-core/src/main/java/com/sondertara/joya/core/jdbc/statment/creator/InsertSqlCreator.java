package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
@Slf4j
public class InsertSqlCreator extends GenericSqlCreator {
    private final boolean ignoreNull;


    public InsertSqlCreator(TableMetaContext entity, boolean ignoreNull) {
        super(entity);
        this.ignoreNull = ignoreNull;
    }

    public InsertSqlCreator(TableMetaContext entity) {
        this(entity, false);
    }

    @Override
    protected List<String> lookColumnNames() {
        List<String> keys = new ArrayList<>();
        if (ignoreNull) {
            for (Map.Entry<String, Object> entry : this.tableMetaContext.getData().entrySet()) {
                if (null != entry.getValue()) {
                    String key = entry.getKey();
                    if (JoyaJdbc.config().isColumnUppercase()) {
                        keys.add(key.toUpperCase(Locale.US));
                    } else {
                        keys.add(key.toLowerCase(Locale.US));
                    }

                }
            }
        } else {
            keys = super.lookColumnNames();
        }
        return keys;
    }

    protected void checkContext() {
        if (CollectionUtils.isEmpty(this.tableMetaContext.getMetaDefinition().getColumnNames())) {
            throw new IllegalStateException("Columns must not be empty");
        }

        if (CollectionUtils.isEmpty(this.tableMetaContext.getData())) {
            throw new IllegalStateException("Insert columns by data[TableMetaContext#data] must not be empty");
        }
    }


    @Override
    protected String createInternal() {
        checkContext();

        StringBuilder insertStatement = new StringBuilder();
        insertStatement.append("INSERT INTO ");
        insertStatement.append(getTableName());
        insertStatement.append(" (");
        int columnCount = 0;


        List<String> generatedKeySet = getGeneratedKeyNames().stream().map(s -> {
            if (JoyaJdbc.config().isColumnUppercase()) {
                return s.toUpperCase(Locale.US);
            } else {
                return s.toLowerCase(Locale.US);
            }
        }).collect(Collectors.toList());
        if (getDeclaredColumns().isEmpty()) {
            for (String columnName : lookColumnNames()) {
                if (generatedKeySet.contains(columnName)) {
                    continue;
                }
                columnCount++;
                if (columnCount > 1) {
                    insertStatement.append(", ");
                }
                insertStatement.append(columnName);
                Object value = tableMetaContext.getData().get(columnName);
                parameters.add(PreparedParameterBinder.builder().position(columnCount).name(columnName).value(value).build());
            }
        } else {
            for (String columnName : getDeclaredColumns()) {
                if (generatedKeySet.contains(columnName)) {
                    continue;
                }
                columnCount++;
                if (columnCount > 1) {
                    insertStatement.append(", ");
                }
                insertStatement.append(columnName);
                parameters.add(PreparedParameterBinder.builder().position(columnCount).name(columnName).value(tableMetaContext.getData().get(columnName)).build());

            }
        }
        insertStatement.append(") VALUES(");
        String params = String.join(", ", Collections.nCopies(columnCount, "?"));
        insertStatement.append(params);
        insertStatement.append(')');
        return insertStatement.toString();
    }
}
