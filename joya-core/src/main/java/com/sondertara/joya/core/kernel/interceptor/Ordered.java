package com.sondertara.joya.core.kernel.interceptor;

/**
 * 有序扩展点标记接口。
 * <p>
 * 排序规则：{@code order()} 升序，同值按 {@code Class.getName()} 字典序保证确定性。
 */
public interface Ordered {
    int HIGHEST = Integer.MIN_VALUE;
    int LOWEST = Integer.MAX_VALUE;

    int order();
}
