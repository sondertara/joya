package com.sondertara.joya.core.builder;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.joya.core.query.criterion.SelectCriterion;

import java.util.function.UnaryOperator;

/**
 * (non-javadoc)
 * the select builder
 *
 * @author huangxiaohu
 */
public interface SelectBuilder {

    /**
     * sql select part
     * <p>
     * example: [t0.user_name] or [t0.usr_name AS userName]
     * select 要查询的列 格式为[t0.user_name] 或者[t0.usr_name AS userName]
     *
     * @param columns the query columns
     * @return the next builder
     */
    FromBuilder select(String... columns);

    /**
     * sql select part
     * <p>
     * this function will query all columns (select *),you can use {@link #wrapColumn(String...)} together to assign alias
     * <p>
     * 查询表的全部列,对于联表查询 如果字段名字有重复,只保留前面一个字段,可以配置specificS来指定字段别名
     *
     * @return the next builder
     */
    FromBuilder select();

    /**
     * the special query column like as alias.
     * <p>
     * 特殊select 字段 搭配select(),指定字段的别名
     *
     * @param selectFields the special column,usually is the column alias
     * @return this builder
     */
    SelectBuilder wrapColumn(String... selectFields);

    /**
     * select single column
     * 选择一个字段
     *
     * @param f1  column
     * @param <T> entity
     * @return the next builder
     */
    <T> FromBuilder select(TaraFunction<T, ?> f1);

    /**
     * select two  column
     *
     * @param f1   column1
     * @param f2   column2
     * @param <T1> entity1
     * @param <T2> entity2
     * @return query
     */
    <T1, T2> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7, T8> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7, T8, T9> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9, TaraFunction<T10, ?> f10);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9, TaraFunction<T10, ?> f10, TaraFunction<T11, ?> f11);

    /**
     * (non-javadoc)
     */
    <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> FromBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3, TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6, TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9, TaraFunction<T10, ?> f10, TaraFunction<T11, ?> f11, TaraFunction<T12, ?> f12);

    /**
     * the lambda select, use {@link #wrapColumn(String...)} (String...)} the resolve alias
     * lambda select 同样冲突字段需要调用specificS()
     *
     * @param func select field
     * @return query
     */
    FromBuilder select(UnaryOperator<SelectCriterion> func);

}