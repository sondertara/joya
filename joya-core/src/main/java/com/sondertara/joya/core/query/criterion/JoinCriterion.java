package com.sondertara.joya.core.query.criterion;


import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.cache.AliasCacheHelper;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.JoinPart;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.sondertara.joya.core.constant.JoyaConst.MAX_JOIN_COUNT;

/**
 * the form  part of query with join
 * <p>
 * only support join three maximum tables. if the query link more table maybe use more subQuery
 *
 * @author huangxiaohu
 */
public class JoinCriterion {

    /**
     * from segments
     */
    private final LinkedList<JoinPart> segments;

    /**
     * the join type {@link JoinType};
     */
    private final List<String> join;
    /**
     * the count tables maximum is three
     */
    private final Set<String> tableNames;

    private final AliasCacheHelper aliasCacheHelper;


    /**
     *
     */
    public JoinCriterion(AliasCacheHelper aliasCacheHelper) {
        this.aliasCacheHelper = aliasCacheHelper;
        this.segments = new LinkedList<>();
        join = new ArrayList<>(2);
        tableNames = new HashSet<>();
    }

    public LinkedList<JoinPart> getJoinSegments() {

        return this.segments;

    }

    /**
     * join
     *
     * @param left  first join part
     * @param right second join part
     * @param <T>   generic of entity
     * @param <R>   generic
     * @return this
     */
    public <T, R> JoinCriterion join(IGetter<T, ?> left, IGetter<R, ?> right) {
        setSegments(JoinType.JOIN, left, right);
        return this;
    }

    /**
     * left join
     *
     * @param left  first join part
     * @param right second join part
     * @param <T>   generic of entity
     * @param <R>   generic
     * @return this
     */
    public <T, R> JoinCriterion leftJoin(IGetter<T, ?> left, IGetter<R, ?> right) {
        setSegments(JoinType.LEFT_JOIN, left, right);
        return this;
    }

    /**
     * right join
     *
     * @param left  first join part
     * @param right second join part
     * @param <T>   generic of entity
     * @param <R>   generic
     * @return this
     */
    public <T, R> JoinCriterion rightJoin(IGetter<T, ?> left, IGetter<R, ?> right) {
        setSegments(JoinType.RIGHT_JOIN, left, right);

        return this;
    }

    /**
     * get the join fields with NodeList which is a list with head node
     *
     * @return node list for join field
     */
    public LinkedList<JoinPart> getSegments() {
        if (tableNames.size() > JoyaConst.MAX_JOIN_TABLE) {

            throw new TaraException("Only support  three tables associated");

        }
        return segments;
    }

    /**
     * get join type
     *
     * @return array of join type
     */
    public List<String> getJoinType() {
        return join;
    }

    private <T, R> void setSegments(JoinType joinType, IGetter<T, ?> left, IGetter<R, ?> right) {
        if (this.segments.size() > MAX_JOIN_COUNT) {
            throw new TaraException("Only support  two join association ," + "if you use too complicated query why no try to optimize the code.");
        }
        final ColumnAlias columnLeft = this.aliasCacheHelper.getColumn(left);
        ColumnAlias columnRight = this.aliasCacheHelper.getColumn(right);
        setJoinStr(columnLeft, columnRight, joinType);
    }

    /**
     * set join segments
     *
     * @param left     first part
     * @param right    second part
     * @param joinType join type
     */
    private void setJoinStr(ColumnAlias left, ColumnAlias right, JoinType joinType) {
        this.segments.addLast(new JoinPart(left, right, joinType));

        tableNames.add(left.getTableName());
        tableNames.add(right.getTableName());
        this.join.add(joinType.code);
    }

    @Getter
    public enum JoinType {
        /**
         *
         */
        JOIN("JOIN"), LEFT_JOIN("LEFT JOIN"), RIGHT_JOIN("RIGHT JOIN");

        private final String code;

        JoinType(String code) {
            this.code = code;
        }
    }
}
