package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.NativeSqlQuery;

/**
 * (non-javadoc)
 * the start builder
 *
 * @author huangxiaohu
 */
public interface SetupBuilder {
    /**
     * builder the  NativeSqlQuery
     * 构造 NativeSqlQuery
     *
     * @return sql
     */
    NativeSqlQuery build();
}
