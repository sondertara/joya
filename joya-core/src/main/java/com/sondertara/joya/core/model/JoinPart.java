package com.sondertara.joya.core.model;


import com.sondertara.common.base.Assert;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author huangxiaohu
 */
@Getter
public class JoinPart {

    private final ColumnAlias leftColumn;
    private final ColumnAlias rightColumn;

    private final JoinCriterion.JoinType joinType;

    public JoinPart(ColumnAlias leftColumn, ColumnAlias rightColumn, JoinCriterion.JoinType joinType) {
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
        List<ColumnAlias> current = new ArrayList<>();
        current.add(leftColumn);
        current.add(rightColumn);
        current.sort(Comparator.comparing(ColumnAlias::identity));
        List<ColumnAlias> other = new ArrayList<>();
        other.add(part.getLeftColumn());
        other.add(part.getRightColumn());
        other.sort(Comparator.comparing(ColumnAlias::identity));
        return Objects.equals(current.get(0), other.get(0)) && Objects.equals(current.get(1), other.get(1));

    }

    @Override
    public int hashCode() {
        List<ColumnAlias> list = new ArrayList<>();
        list.add(leftColumn);
        list.add(rightColumn);
        list.sort(Comparator.comparing(ColumnAlias::identity));
        return Objects.hash(list.get(0), list.get(1), joinType);
    }
}
