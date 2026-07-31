package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.common.regex.RegexUtils;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.support.annotation.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static com.sondertara.joya.utils.SqlFormatUtils.JDBC_STYLE_PARAM;
import static com.sondertara.joya.utils.SqlFormatUtils.NUMBERED_STYLE_PARAM;

public class NativeQueryParser extends AbstractQueryParser {


    public NativeQueryParser(Class<?> domainClass, Method statementMethod) {
        super(domainClass, statementMethod);
    }

    @Override
    public EntityBasedBoundSql parse() {
        Query annotation = statementMethod.getAnnotation(Query.class);
        if (annotation == null) {
            throw new IllegalStateException("The [com.sondertara.joya.core.annotation.JoyaQuery] annotation is required");
        }
        List<JoyaExecutorSupport.ParameterBinding> bindings = new ArrayList<>();

        boolean usesJpaStyleParameters = false;
        String value = annotation.value();
        if (RegexUtils.contains(NUMBERED_STYLE_PARAM, value)) {
            usesJpaStyleParameters = true;
        }
        if (usesJpaStyleParameters && RegexUtils.contains(JDBC_STYLE_PARAM, value)) {
            throw new IllegalArgumentException("Mixing of ? parameters and other forms like ?1 is not supported!");
        }
        int bindIndex = 1;
        if (usesJpaStyleParameters) {
            int greatestParameterIndex = -1;
            List<String> all = RegexUtils.findAll(NUMBERED_STYLE_PARAM, value, 0);
            for (String s : all) {
                int index = Integer.parseInt(s.substring(1));
                JoyaExecutorSupport.ParameterBinding binding = new JoyaExecutorSupport.ParameterBinding(index);
                bindings.add(binding);
                greatestParameterIndex = Math.max(index, greatestParameterIndex);
            }
        } else {
            List<String> all = RegexUtils.findAll(JDBC_STYLE_PARAM, value, 0);
            for (int i = 0; i < all.size(); i++) {
                JoyaExecutorSupport.ParameterBinding binding = new JoyaExecutorSupport.ParameterBinding(i + 1);
                bindings.add(binding);
            }
        }

        java.lang.reflect.Type returnType = statementMethod.getGenericReturnType();
        return new DefaultEntityBasedBoundSql(returnType, value, bindings);

    }

    @Override
    public int lastIndex() {
        return 0;
    }

}