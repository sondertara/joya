package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.statement.LockMode;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.SortDirection;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询子句状态（Query 的内部数据模型），只保存“查什么/怎么筛”，不渲染、不执行。
 * 集合字段懒初始化：简单查询不提前分配 JoinCriterion / WhereCriterion / ArrayList。
 */
final class QuerySpec<T> {

    record OrderSpec(ColumnMetaProvider column, SortDirection direction) {
    }

    private final Class<T> rootType;
    private JoinCriterion joinCriterion;
    private WhereCriterion whereCriterion;
    private List<ColumnMetaProvider> groupByColumns;
    private WhereCriterion havingCriterion;
    private List<OrderSpec> orderSpecs;
    private boolean distinct;
    private Integer offset;
    private Integer limit;
    private LockMode lockMode = LockMode.NONE;

    QuerySpec(Class<T> rootType) {
        this.rootType = rootType;
    }

    Class<T> rootType() {
        return rootType;
    }

    JoinCriterion joinCriterion() {
        if (joinCriterion == null) {
            joinCriterion = new JoinCriterion();
        }
        return joinCriterion;
    }

    WhereCriterion whereCriterion() {
        if (whereCriterion == null) {
            whereCriterion = new WhereCriterion(LinkType.AND);
        }
        return whereCriterion;
    }

    List<ColumnMetaProvider> groupByColumns() {
        if (groupByColumns == null) {
            groupByColumns = new ArrayList<>();
        }
        return groupByColumns;
    }

    WhereCriterion havingCriterion() {
        if (havingCriterion == null) {
            havingCriterion = new WhereCriterion(LinkType.AND);
        }
        return havingCriterion;
    }

    List<OrderSpec> orderSpecs() {
        if (orderSpecs == null) {
            orderSpecs = new ArrayList<>();
        }
        return orderSpecs;
    }

    boolean distinct() {
        return distinct;
    }

    void distinct(boolean value) {
        this.distinct = value;
    }

    Integer offset() {
        return offset;
    }

    Integer limit() {
        return limit;
    }

    void limit(Integer offset, Integer limit) {
        this.offset = offset;
        this.limit = limit;
    }

    LockMode lockMode() {
        return lockMode;
    }

    void lockMode(LockMode lockMode) {
        this.lockMode = lockMode == null ? LockMode.NONE : lockMode;
    }

    QuerySpec<T> copy() {
        QuerySpec<T> copy = new QuerySpec<>(rootType);
        if (joinCriterion != null && !joinCriterion.isEmpty()) {
            copy.joinCriterion = joinCriterion.copy();
        }
        if (whereCriterion != null && !whereCriterion.isEmpty()) {
            copy.whereCriterion = whereCriterion.copy();
        }
        if (groupByColumns != null && !groupByColumns.isEmpty()) {
            copy.groupByColumns = new ArrayList<>(groupByColumns);
        }
        if (havingCriterion != null && !havingCriterion.isEmpty()) {
            copy.havingCriterion = havingCriterion.copy();
        }
        if (orderSpecs != null && !orderSpecs.isEmpty()) {
            copy.orderSpecs = new ArrayList<>(orderSpecs);
        }
        copy.distinct = distinct;
        copy.offset = offset;
        copy.limit = limit;
        copy.lockMode = lockMode;
        return copy;
    }
}
