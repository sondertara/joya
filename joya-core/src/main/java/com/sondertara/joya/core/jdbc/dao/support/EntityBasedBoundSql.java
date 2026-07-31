package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;

import java.util.List;

public interface EntityBasedBoundSql  {

    java.lang.reflect.Type returnType();
    String getSqlString();

    boolean isQuery();

    List<JoyaExecutorSupport.ParameterBinding> parameterBinding();


}
