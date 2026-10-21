package com.sondertara.joya.core.jdbc.dao.support;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.support.annotation.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.sondertara.joya.utils.SqlFormatUtils.NUMBERED_STYLE_PARAM;
import static com.sondertara.joya.utils.SqlFormatUtils.JDBC_STYLE_PARAM;

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
        Matcher numberedMatcher = NUMBERED_STYLE_PARAM.matcher(value);
        if (numberedMatcher.find()) {
            usesJpaStyleParameters = true;
        }
        if (usesJpaStyleParameters && JDBC_STYLE_PARAM.matcher(value).find()) {
            throw new IllegalArgumentException("Mixing of ? parameters and other forms like ?1 is not supported!");
        }
        int bindIndex = 1;
        if (usesJpaStyleParameters) {
            int greatestParameterIndex = -1;
            Matcher m = NUMBERED_STYLE_PARAM.matcher(value);
            List<String> all = new ArrayList<>();
            while (m.find()) {
                all.add(m.group());
            }
            for (String s : all) {
                int index = Integer.parseInt(s.substring(1));
                JoyaExecutorSupport.ParameterBinding binding = new JoyaExecutorSupport.ParameterBinding(index);
                bindings.add(binding);
                greatestParameterIndex = Math.max(index, greatestParameterIndex);
            }
        } else {
            Matcher m = JDBC_STYLE_PARAM.matcher(value);
            int count = 0;
            while (m.find()) {
                count++;
            }
            for (int i = 0; i < count; i++) {
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