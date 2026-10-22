package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.query.JoinPart;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.exceptions.DbException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.LinkedList;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 表关联（JOIN）条件收集器。
 *
 * <p>在 API 调用期仅记录 {@link ColumnMetaProvider}、{@link JoinType} 与可选的 {@code ON} 附加条件，
 * 并校验自关联与重复关联，不预先分配表别名。
 *
 * @author huangxiaohu
 * @date 2021/11/15 16:44
 * @since 1.0.0
 */
@Slf4j
public class JoinCriterion {

    private final LinkedList<JoinPart> segments;

    public JoinCriterion() {
        this.segments = new LinkedList<>();
    }

    public static JoinCriterion create() {
        return new JoinCriterion();
    }

    public LinkedList<JoinPart> getJoinSegments() {
        return this.segments;
    }

    /**
     * Join other table, default is {@link JoinType#INNER_JOIN}.
     */
    public <T1, T2> JoinCriterion join(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.INNER_JOIN, null);
    }

    /**
     * Inner join with extra {@code ON} condition (e.g. {@code ON a = b AND t1.deleted = 0}).
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.INNER_JOIN, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Left join table.
     */
    public <T1, T2> JoinCriterion leftJoin(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.LEFT_JOIN, null);
    }

    /**
     * Left join table with extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion leftJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.LEFT_JOIN, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Right join table.
     */
    public <T1, T2> JoinCriterion rightJoin(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.RIGHT_JOIN, null);
    }

    /**
     * Right join table with extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion rightJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.RIGHT_JOIN, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    public LinkedList<JoinPart> getSegments() {
        return segments;
    }

    /**
     * Join table with explicit {@link JoinType}.
     */
    public <T1, T2> JoinCriterion join(IGetter<T1, ?> left, IGetter<T2, ?> right, JoinType joinType) {
        return join(left, right, joinType, null);
    }

    /**
     * Join table with explicit {@link JoinType} and optional extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left,
            IGetter<T2, ?> right,
            JoinType joinType,
            @Nullable UnaryOperator<WhereCriterion> onCondition) {
        ColumnMetaProvider columnLeft = ColumnMetaProvider.fromGetter(left);
        ColumnMetaProvider columnRight = ColumnMetaProvider.fromGetter(right);
        if (columnLeft.entityClass() != null && Objects.equals(columnLeft.entityClass(), columnRight.entityClass())) {
            throw new DbException(
                    "The join tables are same; review your code: [" + columnLeft.entityClass().getName() + "]");
        }
        WhereCriterion onCriterion = null;
        if (onCondition != null) {
            WhereCriterion built = onCondition.apply(new WhereCriterion(LinkType.AND));
            if (built != null && !built.isEmpty()) {
                onCriterion = built;
            }
        }
        JoinPart joinPart = new JoinPart(columnLeft, columnRight, joinType, onCriterion);
        if (this.segments.contains(joinPart)) {
            throw new DbException("Duplicate join relation between ["
                    + columnLeft.canonicalKey() + "] and [" + columnRight.canonicalKey() + "]");
        }
        this.segments.addLast(joinPart);
        return this;
    }

    public boolean isEmpty() {
        return this.segments.isEmpty();
    }

    @Getter
    public enum JoinType {
        /**
         * Join type of two table
         */
        JOIN("JOIN"),
        INNER_JOIN("JOIN"),
        LEFT_JOIN("LEFT JOIN"),
        RIGHT_JOIN("RIGHT JOIN");

        private final String code;

        JoinType(String code) {
            this.code = code;
        }
    }
}
