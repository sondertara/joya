package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.utils.LambdaUtils;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 位置映射的 record 顺序校验。
 *
 * <p>当映射函数是 record 构造器引用（{@code X::new}）时，若已选列的属性名与 record 字段名
 * <b>集合相同但顺序不同</b>，说明列顺序写反了（同类型字段编译器无法发现），直接报错；
 * 名字对不上（聚合列、改名字段）时不做校验，按位置映射。
 */
final class RecordOrderVerifier {

    private static final Map<Class<?>, Optional<Class<?>>> CONSTRUCTED_RECORDS = new ConcurrentHashMap<>();

    private RecordOrderVerifier() {
    }

    /**
     * 解析映射函数构造的 record 类型；不是 record 构造器引用时返回 {@code null}。
     */
    static Class<?> constructedRecord(Object mapper) {
        if (mapper == null) {
            return null;
        }
        return CONSTRUCTED_RECORDS.computeIfAbsent(mapper.getClass(), k -> resolve(mapper)).orElse(null);
    }

    /**
     * 校验列顺序与 record 字段顺序是否一致。
     *
     * @param record      record 类型
     * @param columnNames 已选列的属性名（表达式列为 {@code null}）
     */
    static void verify(Class<?> record, List<String> columnNames) {
        RecordComponent[] components = record.getRecordComponents();
        if (components == null || components.length != columnNames.size() || columnNames.contains(null)) {
            return;
        }
        List<String> expected = new ArrayList<>(components.length);
        for (RecordComponent component : components) {
            expected.add(component.getName().toLowerCase(Locale.ROOT));
        }
        List<String> actual = new ArrayList<>(columnNames.size());
        for (String name : columnNames) {
            actual.add(name.toLowerCase(Locale.ROOT));
        }
        if (!expected.equals(actual) && new HashSet<>(expected).equals(new HashSet<>(actual))) {
            List<String> declared = new ArrayList<>(components.length);
            for (RecordComponent component : components) {
                declared.add(component.getName());
            }
            throw new IllegalStateException("Selected column order " + columnNames
                    + " does not match record " + record.getSimpleName() + " component order " + declared
                    + "; reorder select(...) to match the record constructor");
        }
    }

    static Class<?>[] parameterTypes(Class<?> record) {
        RecordComponent[] components = record.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        return types;
    }

    private static Optional<Class<?>> resolve(Object mapper) {
        try {
            Method writeReplace = mapper.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replaced = writeReplace.invoke(mapper);
            if (replaced instanceof SerializedLambda lambda && "<init>".equals(lambda.getImplMethodName())) {
                Class<?> type = LambdaUtils.loadClass(lambda.getImplClass().replace('/', '.'));
                if (type.isRecord()) {
                    return Optional.of(type);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 非可序列化 lambda 或非构造器引用：不校验
        }
        return Optional.empty();
    }
}
