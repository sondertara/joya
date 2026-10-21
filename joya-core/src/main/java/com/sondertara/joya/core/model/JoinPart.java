package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.utils.Assert;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 关联查询（JOIN）关系描述单元。
 *
 * <p>持有左右关联列的 {@link ColumnMetaProvider}（惰性列元数据），在构造期不触发 {@code t0 / t1} 表别名分配，
 * 直到查询渲染期由 {@link com.sondertara.joya.core.query.RenderContext} 统一解析。
 *
 * @author huangxiaohu
 */
@Getter
public class JoinPart {

    private final ColumnMetaProvider leftColumn;
    private final ColumnMetaProvider rightColumn;
    private final JoinCriterion.JoinType joinType;

    public JoinPart(ColumnMetaProvider leftColumn, ColumnMetaProvider rightColumn, JoinCriterion.JoinType joinType) {
        Assert.notNull(leftColumn);
        Assert.notNull(rightColumn);
        Assert.notNull(joinType);
        this.leftColumn = leftColumn;
        this.rightColumn = rightColumn;
        this.joinType = joinType;
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
        List<String> current = new ArrayList<>(2);
        current.add(leftColumn.canonicalKey());
        current.add(rightColumn.canonicalKey());
        current.sort(String::compareTo);

        List<String> other = new ArrayList<>(2);
        other.add(part.getLeftColumn().canonicalKey());
        other.add(part.getRightColumn().canonicalKey());
        other.sort(String::compareTo);
        return Objects.equals(current.get(0), other.get(0)) && Objects.equals(current.get(1), other.get(1));
    }

    @Override
    public int hashCode() {
        List<String> list = new ArrayList<>(2);
        list.add(leftColumn.canonicalKey());
        list.add(rightColumn.canonicalKey());
        list.sort(String::compareTo);
        return Objects.hash(list.get(0), list.get(1), joinType);
    }
}
