package com.sondertara.joya.core.query;

import com.sondertara.joya.core.kernel.statement.SqlNode;
import lombok.Getter;

/**
 * 关联类型。
 */
@Getter
public enum JoinType {
    JOIN("JOIN", SqlNode.JoinKind.JOIN),
    INNER_JOIN("JOIN", SqlNode.JoinKind.JOIN),
    LEFT_JOIN("LEFT JOIN", SqlNode.JoinKind.LEFT_JOIN),
    RIGHT_JOIN("RIGHT JOIN", SqlNode.JoinKind.RIGHT_JOIN);

    private final String code;
    private final SqlNode.JoinKind joinKind;

    JoinType(String code, SqlNode.JoinKind joinKind) {
        this.code = code;
        this.joinKind = joinKind;
    }

    public SqlNode.JoinKind toJoinKind() {
        return joinKind;
    }
}
