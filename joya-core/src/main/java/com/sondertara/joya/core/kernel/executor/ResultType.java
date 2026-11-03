package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.utils.LinkedCaseInsensitiveMap;

import java.net.URI;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 结果类型分类：全库唯一的“这个 Class 该怎么映射”判断。
 *
 * <p>位于 kernel 层，使 {@code query} 渲染层无需依赖 {@code jdbc} 适配层即可表达映射意图；
 * 真正的 {@code RowMapper} 由 {@link RowMapperFactory} 在上层注入。
 */
public enum ResultType {

    /** 单列标量：走 {@code SingleColumnRowMapper}。 */
    SCALAR,

    /** JavaBean：按属性名映射。 */
    BEAN,

    /** Java Record：按组件名/构造器映射。 */
    RECORD,

    /** Map 结果：按列标签映射（返回 {@code LinkedCaseInsensitiveMap}）。 */
    MAP,

    /** 不支持的结果类型：应在计划构建期直接拒绝。 */
    UNSUPPORTED;

    public static ResultType classify(Class<?> type) {
        if (type == null || type == Object.class || type == void.class) {
            return UNSUPPORTED;
        }
        if (Map.class.isAssignableFrom(type)) {
            // MapRowMapper 固定返回 LinkedCaseInsensitiveMap（AbstractMap 子类）；
            // 仅接受能容纳该实现的类型，TreeMap 等有排序语义的具体类型直接拒绝。
            return type.isAssignableFrom(LinkedCaseInsensitiveMap.class) ? MAP : UNSUPPORTED;
        }
        if (type.isArray()) {
            return type == byte[].class ? SCALAR : UNSUPPORTED;
        }
        if (isScalar(type)) {
            return SCALAR;
        }
        if (type.isRecord()) {
            return RECORD;
        }
        if (type.isInterface() || type.isAnnotation()) {
            return UNSUPPORTED;
        }
        return BEAN;
    }

    /** 是否为单列标量类型。 */
    public static boolean isScalar(Class<?> type) {
        if (type == null) {
            return false;
        }
        return type.isPrimitive()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Date.class.isAssignableFrom(type)
                || Temporal.class.isAssignableFrom(type)
                || UUID.class == type
                || byte[].class == type
                || Blob.class.isAssignableFrom(type)
                || Clob.class.isAssignableFrom(type)
                || Enum.class.isAssignableFrom(type)
                || URI.class == type
                || URL.class == type;
    }
}
