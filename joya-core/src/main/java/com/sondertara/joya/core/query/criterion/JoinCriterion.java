package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.query.JoinPart;
import com.sondertara.joya.core.query.JoinType;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.exceptions.DbException;
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
public class JoinCriterion {

    private final LinkedList<JoinPart> segments;

    public JoinCriterion() {
        this.segments = new LinkedList<>();
    }

    private JoinCriterion(LinkedList<JoinPart> segments) {
        this.segments = segments;
    }

    public static JoinCriterion create() {
        return new JoinCriterion();
    }

    /**
     * 深拷贝当前 JOIN 条件收集器（各 {@link JoinPart} 的附加 ON 条件同步深拷贝）。
     */
    public JoinCriterion copy() {
        LinkedList<JoinPart> copied = new LinkedList<>();
        for (JoinPart part : this.segments) {
            WhereCriterion onCopy = part.getOnCriterion() instanceof WhereCriterion wc ? wc.copy() : null;
            copied.add(new JoinPart(
                    part.getLeftColumn(),
                    part.getRightColumn(),
                    part.getJoinType(),
                    part.getAlias(),
                    onCopy));
        }
        return new JoinCriterion(copied);
    }

    public LinkedList<JoinPart> getJoinSegments() {
        return this.segments;
    }

    /**
     * Join other table, default is {@link JoinType#INNER_JOIN}.
     */
    public <T1, T2> JoinCriterion join(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.INNER_JOIN, null, null);
    }

    /**
     * Inner join with explicit table alias for the joined target table.
     */
    public <T1, T2> JoinCriterion join(IGetter<T1, ?> left, IGetter<T2, ?> right, String alias) {
        return join(left, right, JoinType.INNER_JOIN, validateAlias(alias), null);
    }

    /**
     * Inner join with extra {@code ON} condition (e.g. {@code ON a = b AND t1.deleted = 0}).
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.INNER_JOIN, null, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Inner join with explicit table alias and extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left, IGetter<T2, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.INNER_JOIN, validateAlias(alias),
                Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Left join table.
     */
    public <T1, T2> JoinCriterion leftJoin(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.LEFT_JOIN, null, null);
    }

    /**
     * Left join table with explicit table alias for the joined target table.
     */
    public <T1, T2> JoinCriterion leftJoin(IGetter<T1, ?> left, IGetter<T2, ?> right, String alias) {
        return join(left, right, JoinType.LEFT_JOIN, validateAlias(alias), null);
    }

    /**
     * Left join table with extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion leftJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.LEFT_JOIN, null, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Left join table with explicit table alias and extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion leftJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.LEFT_JOIN, validateAlias(alias),
                Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Right join table.
     */
    public <T1, T2> JoinCriterion rightJoin(IGetter<T1, ?> left, IGetter<T2, ?> right) {
        return join(left, right, JoinType.RIGHT_JOIN, null, null);
    }

    /**
     * Right join table with explicit table alias for the joined target table.
     */
    public <T1, T2> JoinCriterion rightJoin(IGetter<T1, ?> left, IGetter<T2, ?> right, String alias) {
        return join(left, right, JoinType.RIGHT_JOIN, validateAlias(alias), null);
    }

    /**
     * Right join table with extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion rightJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.RIGHT_JOIN, null, Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }

    /**
     * Right join table with explicit table alias and extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion rightJoin(
            IGetter<T1, ?> left, IGetter<T2, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, JoinType.RIGHT_JOIN, validateAlias(alias),
                Objects.requireNonNull(onCondition, "onCondition must not be null"));
    }


    /**
     * Join table with explicit {@link JoinType}.
     */
    public <T1, T2> JoinCriterion join(IGetter<T1, ?> left, IGetter<T2, ?> right, JoinType joinType) {
        return join(left, right, joinType, null, null);
    }

    /**
     * Join table with explicit {@link JoinType} and optional extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left,
            IGetter<T2, ?> right,
            JoinType joinType,
            @Nullable UnaryOperator<WhereCriterion> onCondition) {
        return join(left, right, joinType, null, onCondition);
    }

    /**
     * Join table with explicit {@link JoinType}, optional table {@code alias} and optional extra {@code ON} condition.
     */
    public <T1, T2> JoinCriterion join(
            IGetter<T1, ?> left,
            IGetter<T2, ?> right,
            JoinType joinType,
            @Nullable String alias,
            @Nullable UnaryOperator<WhereCriterion> onCondition) {
        ColumnMetaProvider columnLeft = ColumnMetaProvider.fromGetter(left);
        ColumnMetaProvider columnRight = ColumnMetaProvider.fromGetter(right);
        if (columnLeft.entityClass() == null || columnRight.entityClass() == null) {
            String side = columnLeft.entityClass() == null ? "left" : "right";
            throw new IllegalArgumentException("JOIN " + side
                    + " column must reference an entity table; SQL expressions without a table role are not supported");
        }
        String normalizedAlias = alias == null ? null : validateAlias(alias);
        if (normalizedAlias == null && Objects.equals(columnLeft.canonicalKey(), columnRight.canonicalKey())) {
            throw new DbException(
                    "The join columns are identical; review your code or provide an explicit table alias for self-join: ["
                            + columnLeft.canonicalKey() + "]");
        }
        WhereCriterion onCriterion = null;
        if (onCondition != null) {
            WhereCriterion built = onCondition.apply(new WhereCriterion(LinkType.AND));
            if (built != null && !built.isEmpty()) {
                onCriterion = built;
            }
        }
        JoinPart joinPart = new JoinPart(columnLeft, columnRight, joinType, normalizedAlias, onCriterion);
        if (this.segments.contains(joinPart)) {
            throw new DbException("Duplicate join relation between ["
                    + columnLeft.canonicalKey() + "] and [" + columnRight.canonicalKey() + "]");
        }
        this.segments.addLast(joinPart);
        return this;
    }

    private static @Nullable String validateAlias(@Nullable String alias) {
        if (alias == null) {
            return null;
        }
        String trimmed = alias.trim();
        if (!ColumnMetaProvider.SAFE_ALIAS.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table alias (potential SQL injection rejected): " + alias);
        }
        return trimmed;
    }

    public boolean isEmpty() {
        return this.segments.isEmpty();
    }
}
