package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface WhereBuilder extends ExtPartBuilder {

    /**
     * where
     * 使用方式: nativeSql.where(w -> w.eq().ne().in())
     */
    ExtPartBuilder where(UnaryOperator<WhereCriterion> func);


    /**
     * where
     * 使用方式: nativeSql.where(w -> w.eq().ne().in())
     *
     * @param func   条件
     * @param linkOr 是否为or查询 默认为and
     */
    ExtPartBuilder where(UnaryOperator<WhereCriterion> func, boolean linkOr);

    /**
     * where 字段追加 一般用于特殊sql,如联表查询条件、特殊sql处理
     * 指定字段的别名
     *
     * @param whereFields 特殊字段
     */
    WhereBuilder specificW(String... whereFields);
}