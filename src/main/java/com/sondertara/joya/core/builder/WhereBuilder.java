package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface WhereBuilder extends ExtPartBuilder {


    /**
     * lambda where
     * <p>
     * example :nativeSql.where(w -> w.eq().ne().in())
     *
     * @param func the function
     * @return the next builder
     */
    ExtPartBuilder where(UnaryOperator<WhereCriterion> func);


    /**
     * where part with  appoint the condition link type,default is #AND
     * 使用方式: nativeSql.where(w -> w.eq().ne().in())
     *
     * @param func   条件
     * @param linkOr 是否为or查询 默认为and
     * @return the next builder
     */
    ExtPartBuilder where(UnaryOperator<WhereCriterion> func, boolean linkOr);

}