package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlFormatUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 投影计划构建：把「挑哪些列 + 装进什么类型」通过 {@link RenderContext} 翻译为 SELECT 列表与行映射器。
 *
 * <ul>
 *   <li>{@link #auto}：未写 select，按结果类型推列（实体 → 该表全部列；DTO → 按字段名在参与表中唯一匹配）。</li>
 *   <li>{@link #named}：select 后按名字映射（{@code list(X.class)}）。</li>
 *   <li>{@link #single}：select 单列直接取值。</li>
 *   <li>{@link #positional}：select 后按位置映射（{@code list(X::new)}）。</li>
 * </ul>
 */
final class Projections {

    private Projections() {
    }

    // ===== 未写 select：由结果类型决定列 =====

    static <V> Projection<V> auto(Class<V> type, RenderContext ctx) {
        Collection<Class<?>> sources = ctx.registeredEntities();
        if (sources.contains(type)) {
            return entity(type, ctx);
        }
        if (SqlFormatUtils.isPrimitive(type)) {
            throw new IllegalArgumentException("Cannot map rows to " + type.getName()
                    + " without select; use select(column).list() for a single column");
        }
        Map<Class<?>, List<ColumnAlias>> sourceColumns = new LinkedHashMap<>();
        for (Class<?> source : sources) {
            sourceColumns.put(source, ctx.getAllColumns(source));
        }
        List<String> columns = new ArrayList<>();
        for (Field field : properties(type)) {
            String property = field.getName();
            String declaredColumn = PersistenceAnnotations.findColumnName(field).orElse(null);
            List<ColumnAlias> matches = new ArrayList<>();
            List<String> matchedTables = new ArrayList<>();
            for (Map.Entry<Class<?>, List<ColumnAlias>> entry : sourceColumns.entrySet()) {
                ColumnAlias match = findColumn(entry.getValue(), property, declaredColumn);
                if (match != null) {
                    matches.add(match);
                    matchedTables.add(match.getTableName());
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalStateException("Property '" + property + "' of " + type.getSimpleName()
                        + " matches no column of the queried tables; use select(...).list(" + type.getSimpleName() + "::new)");
            }
            if (matches.size() > 1) {
                throw new IllegalStateException("Property '" + property + "' of " + type.getSimpleName()
                        + " is ambiguous across tables " + matchedTables + "; use select(...).list(" + type.getSimpleName() + "::new)");
            }
            columns.add(matches.get(0).identity() + " AS " + property);
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("No mappable property found on " + type.getName());
        }
        return new Projection<>(columns, BeanRowMapper.of(type));
    }

    /** 实体全部列（按实体元数据映射）。 */
    static <V> Projection<V> entity(Class<V> type, RenderContext ctx) {
        List<ColumnAlias> allColumns = ctx.getAllColumns(type);
        List<String> columns = new ArrayList<>(allColumns.size());
        for (ColumnAlias column : allColumns) {
            columns.add(column.identity());
        }
        return new Projection<>(columns, BeanRowMapper.of(type));
    }

    // ===== 写了 select =====

    static <V> Projection<V> named(List<IGetter<?, ?>> selected, Class<V> type, RenderContext ctx) {
        List<String> columns = new ArrayList<>(selected.size());
        Set<String> labels = new HashSet<>();
        for (IGetter<?, ?> column : selected) {
            if (column instanceof Expr<?>) {
                throw new IllegalStateException("Name-based mapping list(" + type.getSimpleName()
                        + ".class) does not support expression columns (aggregate / raw); use list(" + type.getSimpleName() + "::new)");
            }
            ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
            String label = provider.propertyName();
            if (label == null || !labels.add(label.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Duplicate column label '" + label + "' from different tables; use list("
                        + type.getSimpleName() + "::new) to map by position");
            }
            columns.add(provider.resolveSql(ctx) + " AS " + label);
        }
        return new Projection<>(columns, BeanRowMapper.of(type));
    }

    @SuppressWarnings("unchecked")
    static <A> Projection<A> single(IGetter<?, ?> column, RenderContext ctx) {
        Class<?> type = valueType(column);
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        String sql = provider.resolveSql(ctx) + " AS c1";
        return new Projection<>(List.of(sql), rs -> (A) ColumnValues.read(rs, 1, type));
    }

    static <V> Projection<V> positional(List<IGetter<?, ?>> selected, Function<Object[], V> factory, Object mapper,
                                        RenderContext ctx) {
        List<String> columns = new ArrayList<>(selected.size());
        Class<?>[] types = new Class<?>[selected.size()];
        List<String> names = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            IGetter<?, ?> column = selected.get(i);
            ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
            columns.add(provider.resolveSql(ctx) + " AS c" + (i + 1));
            types[i] = valueType(column);
            names.add(column instanceof Expr<?> ? null : provider.propertyName());
        }
        Class<?>[] parameterTypes = null;
        Class<?> record = RecordOrderVerifier.constructedRecord(mapper);
        if (record != null) {
            RecordOrderVerifier.verify(record, names);
            parameterTypes = RecordOrderVerifier.parameterTypes(record);
        }
        return new Projection<>(columns, new PositionalRowMapper<>(types, parameterTypes, factory));
    }

    static Class<?> valueType(IGetter<?, ?> column) {
        if (column instanceof Expr<?> expr) {
            return expr.resultType();
        }
        return LambdaUtils.getReturnType(column);
    }

    // ===== 内部辅助 =====

    private static ColumnAlias findColumn(List<ColumnAlias> columns, String property, String declaredColumn) {
        for (ColumnAlias column : columns) {
            if (property.equals(column.getColumnAlias())) {
                return column;
            }
        }
        if (declaredColumn != null) {
            for (ColumnAlias column : columns) {
                if (declaredColumn.equalsIgnoreCase(column.getColumnName())) {
                    return column;
                }
            }
        }
        return null;
    }

    private static List<Field> properties(Class<?> type) {
        List<Field> result = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    result.add(type.getDeclaredField(component.getName()));
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException("Record component field not found: " + component.getName(), e);
                }
            }
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
                        || PersistenceAnnotations.isTransientPresent(field) || !seen.add(field.getName())) {
                    continue;
                }
                result.add(field);
            }
        }
        return result;
    }
}
