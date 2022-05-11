package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.NativeSqlQuery;

/**
 * @author huangxiaohu
 */
public interface SetupBuilder {
    /**
     * 构造 NativeSqlQuery
     *
     * @return sql
     */
    NativeSqlQuery build();
}
