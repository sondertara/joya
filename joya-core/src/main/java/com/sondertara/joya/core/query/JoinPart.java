package com.sondertara.joya.core.query;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 关联查询（JOIN）关系描述单元。
 *
 * <p>持有左右关联列的 {@link ColumnMetaProvider}（惰性列元数据）、可选的显式表别名 {@code alias} 以及可选的 {@code ON} 附加条件，
 * 在构造期不触发 {@code t0 / t1} 表别名分配，直到查询渲染期由 {@link com.sondertara.joya.core.query.RenderContext} 统一解析。
 *
 * @author huangxiaohu
 */
@Getter
public class JoinPart {

    private final ColumnMetaProvider leftColumn;
    private final ColumnMetaProvider rightColumn;
    private final JoinType joinType;
    @Nullable
    private final String alias;
    @Nullable
    private final OnPredicate onCriterion;

    public JoinPart(ColumnMetaProvider leftColumn, ColumnMetaProvider rightColumn, JoinType joinType) {
        this(leftColumn, rightColumn, joinType, null, null);
    }

    public JoinPart(
            ColumnMetaProvider leftColumn,
            ColumnMetaProvider rightColumn,
            JoinType joinType,
            @Nullable OnPredicate onCriterion) {
        this(leftColumn, rightColumn, joinType, null, onCriterion);
    }

    public JoinPart(
            ColumnMetaProvider leftColumn,
            ColumnMetaProvider rightColumn,
            JoinType joinType,
            @Nullable String alias,
            @Nullable OnPredicate onCriterion) {
        Objects.requireNonNull(leftColumn, "leftColumn must not be null");
        Objects.requireNonNull(rightColumn, "rightColumn must not be null");
        Objects.requireNonNull(joinType, "joinType must not be null");
        this.leftColumn = leftColumn;
        this.rightColumn = rightColumn;
        this.joinType = joinType;
        this.alias = alias == null ? null : alias.trim();
        this.onCriterion = onCriterion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JoinPart part = (JoinPart) o;
        if (!part.getJoinType().equals(this.joinType)) {
            return false;
        }
        String thisAlias = this.alias == null ? null : this.alias.toLowerCase(Locale.ROOT);
        String otherAlias = part.alias == null ? null : part.alias.toLowerCase(Locale.ROOT);
        if (!Objects.equals(thisAlias, otherAlias)) {
            return false;
        }
        if (!Objects.equals(this.onCriterion, part.onCriterion)) {
            return false;
        }
        return Objects.equals(leftColumn.canonicalKey(), part.leftColumn.canonicalKey())
                && Objects.equals(rightColumn.canonicalKey(), part.rightColumn.canonicalKey());
    }

    @Override
    public int hashCode() {
        String normalizedAlias = this.alias == null ? null : this.alias.toLowerCase(Locale.ROOT);
        return Objects.hash(leftColumn.canonicalKey(), rightColumn.canonicalKey(), joinType, normalizedAlias, onCriterion);
    }
}
