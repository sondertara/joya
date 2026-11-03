package com.sondertara.joya.core.query;

/**
 * JOIN {@code ON} 附加条件的最小契约。
 *
 * <p>{@link JoinPart} / {@link RenderContext} 只依赖本接口，而不依赖具体的条件构建器
 * （{@code criterion.WhereCriterion} 实现它），使 {@code query} 根包不反向依赖 {@code criterion}。
 */
public interface OnPredicate extends SqlPredicate {

    /** 是否没有任何条件（空条件不会追加到 {@code ON}）。 */
    boolean isEmpty();
}
