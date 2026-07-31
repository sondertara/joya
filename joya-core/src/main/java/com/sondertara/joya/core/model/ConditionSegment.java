package com.sondertara.joya.core.model;

import lombok.Getter;

import java.util.List;

public abstract class ConditionSegment implements WhereConditionVisitor {

    @Getter
    private final List<WhereConditionVisitor> conditions;

    @Getter
    private final boolean subQuery;

    public ConditionSegment(List<WhereConditionVisitor> conditions, boolean subQuery) {
        this.conditions = conditions;
        this.subQuery = subQuery;
    }

}
