package com.sondertara.joya.core.query;

public interface DeclaredQuery {

    String getQueryString();

    boolean usesJpaStyleParameters();

    boolean hasParams();
}
