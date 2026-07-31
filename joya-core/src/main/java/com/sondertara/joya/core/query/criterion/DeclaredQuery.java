package com.sondertara.joya.core.query.criterion;

public interface DeclaredQuery {

    String getQueryString();

    boolean usesJpaStyleParameters();

    boolean hasParams();
}
