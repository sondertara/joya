package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.support.annotation.Query;
import com.sondertara.joya.core.kernel.dialect.DbType;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class NativeQueryParser extends AbstractQueryParser {

    private final @Nullable DbType dbType;

    public NativeQueryParser(Class<?> domainClass, Method statementMethod) {
        this(domainClass, statementMethod, null);
    }

    public NativeQueryParser(Class<?> domainClass, Method statementMethod, @Nullable DbType dbType) {
        super(domainClass, statementMethod);
        this.dbType = dbType;
    }

    @Override
    public EntityBasedBoundSql parse() {
        Query annotation = statementMethod.getAnnotation(Query.class);
        if (annotation == null) {
            throw new IllegalStateException("The [com.sondertara.joya.core.jdbc.support.annotation.Query] annotation is required");
        }
        String value = annotation.value();
        List<Integer> numberedIndices = new ArrayList<>();
        int positionalCount = 0;
        for (com.sondertara.joya.utils.SqlFormatUtils.Placeholder placeholder
                : com.sondertara.joya.utils.SqlFormatUtils.scanPlaceholders(value, dbType)) {
            if (placeholder.isNumbered()) {
                if (placeholder.index() < 1) {
                    throw new IllegalArgumentException(
                            "Numbered SQL parameter index must be greater than zero: ?" + placeholder.index());
                }
                numberedIndices.add(placeholder.index());
            } else {
                positionalCount++;
            }
        }

        if (!numberedIndices.isEmpty() && positionalCount > 0) {
            throw new IllegalArgumentException("Mixing of ? parameters and other forms like ?1 is not supported!");
        }

        List<JoyaExecutorSupport.ParameterBinding> bindings = new ArrayList<>();
        if (!numberedIndices.isEmpty()) {
            for (int index : numberedIndices) {
                bindings.add(new JoyaExecutorSupport.ParameterBinding(index));
            }
        } else {
            for (int i = 0; i < positionalCount; i++) {
                bindings.add(new JoyaExecutorSupport.ParameterBinding(i + 1));
            }
        }

        java.lang.reflect.Type returnType = statementMethod.getGenericReturnType();
        return new DefaultEntityBasedBoundSql(returnType, value, bindings, dbType);
    }

    @Override
    public int lastIndex() {
        return 0;
    }
}