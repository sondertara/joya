package com.sondertara.joya.core.query.builder;

import com.sondertara.common.function.IGetter;
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
    ExtPartBuilder groupBy(String ...groupBySegment);

  <T> ExtPartBuilder groupBy(IGetter<T,?> fn);

    /**
     * having
     * <p>
     * example:nativeSql.having(h -> h.eq().ne().in())
     *
     * @param func the function
     * @return the builder
     */
    ExtPartBuilder having(String query);

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
    <T> ExtPartBuilder orderBy(IGetter<T, ?> fn, OrderParam.OrderBy orderBy);

    /**
     * order by with string
     *
     * @param orderBySegment order by params
     * @return the builder
     */
    ExtPartBuilder orderBy(String... orderBySegment);
    default SetupBuilder limit(int n){
        return limit(0,n);
    }

    SetupBuilder limit(int offset,int n);
}
