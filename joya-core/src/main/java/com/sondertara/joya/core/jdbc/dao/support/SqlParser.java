package com.sondertara.joya.core.jdbc.dao.support;

import java.lang.reflect.Method;

public interface SqlParser<T> {

    T parse();

}
