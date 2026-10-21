package com.sondertara.joya.core.tx;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 事务状态（不变量 I2 的可执行形式）。
 *
 * <p>状态迁移规则以 {@code TRANSITIONS} 为<b>唯一来源</b>——代码与文档同源，
 * 非法迁移抛 {@link IllegalTxStateException} 并列出合法目标集。
 *
 * <p>刻意保持 5 态：2PC 中间态（{@code PREPARING}/{@code PREPARED}/{@code COMMITTING}/
 * {@code ROLLING_BACK}/{@code UNKNOWN}）在有序 1PC 下<b>永不执行</b>，属死代码 + 认知负担，
 * 已移出核心；XA 插件需要时自行扩展状态机。
 *
 * <p>关键约束：{@code MARKED_ROLLBACK} <b>不可</b>迁移到 {@code COMMITTED}（不变量 I7）。
 */
public enum TxState {

    /** 未开启 */
    NONE,
    /** 事务进行中 */
    ACTIVE,
    /** 已被标记仅回滚（内层回滚 / 业务主动标记） */
    MARKED_ROLLBACK,
    /** 已提交（终态） */
    COMMITTED,
    /** 已回滚（终态） */
    ROLLED_BACK;

    private static final Map<TxState, Set<TxState>> TRANSITIONS = Map.of(
            NONE,            EnumSet.of(ACTIVE, ROLLED_BACK),
            ACTIVE,          EnumSet.of(ACTIVE, MARKED_ROLLBACK, COMMITTED, ROLLED_BACK),
            // I7：标记回滚后只能走向 ROLLED_BACK，绝不能 COMMITTED
            MARKED_ROLLBACK, EnumSet.of(ROLLED_BACK),
            COMMITTED,       EnumSet.noneOf(TxState.class),
            ROLLED_BACK,     EnumSet.noneOf(TxState.class)
    );

    /**
     * 断言可以迁移到目标状态，非法迁移抛异常。
     *
     * @param target 目标状态
     * @throws IllegalTxStateException 迁移非法
     */
    public void assertTransitionTo(TxState target) {
        Set<TxState> allowed = TRANSITIONS.getOrDefault(this, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalTxStateException(this, target, allowed);
        }
    }

    /** 该状态的全部合法目标（用于错误信息与文档生成）。 */
    public Set<TxState> allowedTransitions() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    /** 是否终态。 */
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK;
    }
}
