package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.utils.LambdaUtils;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 位置映射的 record 组件顺序校验与组件元数据缓存。
 */
final class RecordOrderVerifier {

    private static final class Holder {
        final boolean stateless;
        volatile @Nullable Optional<Class<?>> recordType;

        Holder(boolean stateless) {
            this.stateless = stateless;
        }
    }

    private record RecordSpec(
            List<String> lowerNames,
            Set<String> lowerNameSet,
            List<String> declaredNames,
            Class<?>[] parameterTypes
    ) {
    }

    private static final ClassValue<Holder> CONSTRUCTED_RECORDS = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            return new Holder(type.getDeclaredFields().length == 0);
        }
    };

    private static final ClassValue<RecordSpec> RECORD_SPECS = new ClassValue<>() {
        @Override
        protected RecordSpec computeValue(Class<?> record) {
            RecordComponent[] components = record.getRecordComponents();
            if (components == null || components.length == 0) {
                return new RecordSpec(List.of(), Set.of(), List.of(), new Class<?>[0]);
            }
            List<String> lower = new ArrayList<>(components.length);
            List<String> declared = new ArrayList<>(components.length);
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                String name = components[i].getName();
                declared.add(name);
                lower.add(name.toLowerCase(Locale.ROOT));
                types[i] = components[i].getType();
            }
            return new RecordSpec(List.copyOf(lower), Set.copyOf(lower), List.copyOf(declared), types);
        }
    };

    private RecordOrderVerifier() {
    }

    static @Nullable Class<?> constructedRecord(@Nullable Object mapper) {
        if (mapper == null) {
            return null;
        }
        Holder holder = CONSTRUCTED_RECORDS.get(mapper.getClass());
        if (!holder.stateless) {
            return resolve(mapper).orElse(null);
        }
        Optional<Class<?>> resolved = holder.recordType;
        if (resolved == null) {
            resolved = resolve(mapper);
            holder.recordType = resolved;
        }
        return resolved.orElse(null);
    }

    static void verify(Class<?> record, List<String> columnNames) {
        RecordSpec spec = RECORD_SPECS.get(record);
        if (spec.lowerNames().size() != columnNames.size()) {
            return;
        }
        for (String name : columnNames) {
            if (name == null) {
                return;
            }
        }
        List<String> actual = new ArrayList<>(columnNames.size());
        for (String name : columnNames) {
            actual.add(name.toLowerCase(Locale.ROOT));
        }
        if (!spec.lowerNames().equals(actual) && spec.lowerNameSet().equals(new HashSet<>(actual))) {
            throw new IllegalStateException("Selected column order " + columnNames
                    + " does not match record " + record.getSimpleName() + " component order " + spec.declaredNames()
                    + "; reorder select(...) to match the record constructor");
        }
    }

    static Class<?>[] parameterTypes(Class<?> record) {
        return RECORD_SPECS.get(record).parameterTypes();
    }

    private static Optional<Class<?>> resolve(Object mapper) {
        try {
            Method writeReplace = mapper.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replaced = writeReplace.invoke(mapper);
            if (replaced instanceof SerializedLambda lambda && "<init>".equals(lambda.getImplMethodName())) {
                Class<?> type = LambdaUtils.loadClass(
                        lambda.getImplClass().replace('/', '.'), mapper.getClass().getClassLoader());
                if (type.isRecord()) {
                    return Optional.of(type);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 非可序列化 lambda 或非构造器引用：跳过校验
        }
        return Optional.empty();
    }
}
