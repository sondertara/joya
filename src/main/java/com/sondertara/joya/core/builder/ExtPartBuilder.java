package com.sondertara.joya.core.builder;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface ExtPartBuilder extends SetupBuilder {

    /**
     * group by
     *
     * @param groupBySegment param
     * @return the builder
     */
    ExtPartBuilder groupBy(String groupBySegment);

    /**
     * having
     * <p>
     * example:nativeSql.having(h -> h.eq().ne().in())
     *
     * @param func the function
     * @return the builder
     */
    ExtPartBuilder having(UnaryOperator<WhereCriterion> func);

    /**
     * order by with function
     * <p>
     * example: nativeSql.orderBy("su.age asc")
     *
     * @param fn      the column function
     * @param orderBy the order type
     * @param <T>     the type of table class
     * @return the builder
     */
    <T> ExtPartBuilder orderBy(TaraFunction<T, ?> fn, OrderParam.OrderBy orderBy);

    /**
     * order by with string
     *
     * @param orderBySegment order by params
     * @return the builder
     */
    ExtPartBuilder orderBy(String... orderBySegment);
}
