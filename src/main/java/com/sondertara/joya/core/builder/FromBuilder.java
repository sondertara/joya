package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.criterion.JoinCriterion;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface FromBuilder {

    /**
     * 字符串格式的from语句
     * from 要查询的表以及关联表
     */
    WhereBuilder from(String... tableAndJoinTable);

    /**
     * 根据class获取表，别名为表的顺序t0,t1,t2,t2
     *
     * @param clazz form entity class
     * @return query
     */
    WhereBuilder from(Class<?>... clazz);

    /**
     * 根据class获取表，别名为表的顺序t0,t1,t2,t2
     *
     * @param func join param
     * @return query
     */
    WhereBuilder from(UnaryOperator<JoinCriterion> func);
}