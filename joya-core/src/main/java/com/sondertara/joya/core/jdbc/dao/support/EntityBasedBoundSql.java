package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;

import java.util.List;
import java.util.OptionalInt;

public interface EntityBasedBoundSql {

    java.lang.reflect.Type returnType();

    String getSqlString();

    boolean isQuery();

    List<JoyaExecutorSupport.ParameterBinding> parameterBinding();

    default OptionalInt maxResults() {
        return OptionalInt.empty();
    }

    /**
     * 实际 UPDATE 成功后的回调，默认无操作。
     */
    default void postUpdate() {
    }
}
