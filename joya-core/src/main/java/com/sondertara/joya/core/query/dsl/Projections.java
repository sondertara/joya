package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.query.Expr;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.utils.LambdaUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 投影计划构建：把「挑哪些列 + 装进什么类型」通过 {@link RenderContext} 翻译为 SELECT 列表与行映射器。
 *
 * <ul>
 *   <li>{@link #auto}：未写 select，按结果类型推列（已注册实体 → 该表全部列；单表 DTO/Record → 按字段名匹配主表列；联表非实体 DTO 要求显式 {@code select(...)}）。</li>
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
        ResultType kind = ResultType.classify(type);
        if (kind == ResultType.MAP) {
            throw new IllegalArgumentException("Cannot map all columns to " + type.getName()
                    + " without select; use select(...).list(" + type.getSimpleName()
                    + ".class) or SqlOps.listMap()");
        }
        if (kind == ResultType.SCALAR) {
            throw new IllegalArgumentException("Cannot map rows to " + type.getName()
                    + " without select; use select(column).list() for a single column");
        }
        if (kind == ResultType.UNSUPPORTED) {
            throw new IllegalArgumentException("Cannot map rows to " + type.getName()
                    + ": use a concrete DTO or record type");
        }
        Collection<Class<?>> sources = ctx.registeredEntities();
        if (sources.contains(type)) {
            return entity(type, ctx);
        }
        if (ctx.hasJoins()) {
            throw new IllegalStateException("Cannot auto-project non-entity type " + type.getSimpleName()
                    + " across joined tables without explicit select(...); use select(...).list("
                    + type.getSimpleName() + "::new) or select(...).list(" + type.getSimpleName() + ".class)");
        }
        List<SqlNode> nodes = new ArrayList<>();
        for (Field field : properties(type)) {
            String property = field.getName();
            String declaredColumn = PersistenceAnnotations.findColumnName(field).orElse(null);
            ColumnAlias match = ctx.findColumn(ctx.rootType(), property, declaredColumn);
            if (match == null) {
                throw new IllegalStateException("Property '" + property + "' of " + type.getSimpleName()
                        + " matches no column of " + ctx.rootType().getSimpleName()
                        + "; use select(...).list(" + type.getSimpleName() + "::new)");
            }
            nodes.add(SqlNodes.alias(node(match), property));
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No mappable property found on " + type.getName());
        }
        return Projection.typed(nodes, kind, type, false, ctx);
    }

    /** 实体全部列（按实体元数据映射）。 */
    static <V> Projection<V> entity(Class<V> type, RenderContext ctx) {
        List<ColumnAlias> allColumns = ctx.getAllColumns(type);
        List<SqlNode> nodes = new ArrayList<>(allColumns.size());
        for (ColumnAlias column : allColumns) {
            nodes.add(node(column));
        }
        return Projection.typed(nodes, ResultType.BEAN, type, false, ctx);
    }

    // ===== 写了 select =====

    static <V> Projection<V> named(List<IGetter<?, ?>> selected, Class<V> type, RenderContext ctx) {
        ResultType kind = ResultType.classify(type);
        if (kind == ResultType.UNSUPPORTED) {
            throw new IllegalArgumentException("Cannot map query results to " + type.getName()
                    + ": use a concrete DTO or record type");
        }
        if (kind == ResultType.SCALAR && selected.size() > 1) {
            throw new IllegalArgumentException("Cannot map " + selected.size() + " columns to scalar type "
                    + type.getName() + "; use a concrete DTO or record type, or select a single column");
        }
        List<SqlNode> nodes = new ArrayList<>(selected.size());
        Set<String> labels = new HashSet<>();
        for (IGetter<?, ?> column : selected) {
            if (column instanceof Expr<?> expr && expr.alias() == null) {
                throw new IllegalStateException("Name-based mapping list(" + type.getSimpleName()
                        + ".class) does not support expression columns (aggregate / raw); use list(" + type.getSimpleName() + "::new)");
            }
            ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
            String label = provider.propertyName();
            if (label == null || !labels.add(label.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Duplicate column label '" + label + "' from different tables; use list("
                        + type.getSimpleName() + "::new) to map by position");
            }
            nodes.add(SqlNodes.alias(provider.resolveNode(ctx), label));
        }
        return Projection.typed(nodes, kind, type, hasAggregate(selected), ctx);
    }

    @SuppressWarnings("unchecked")
    static <A> Projection<A> single(IGetter<?, ?> column, RenderContext ctx) {
        Class<?> type = valueType(column);
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        return Projection.mapped(
                List.of(SqlNodes.alias(provider.resolveNode(ctx), "c1")),
                rs -> (A) ColumnValues.read(rs, 1, type),
                column instanceof Expr<?> expr && expr.isAggregate(),
                ctx);
    }

    static <V> Projection<V> positional(List<IGetter<?, ?>> selected, Function<Object[], V> factory, Object mapper,
                                        RenderContext ctx) {
        List<SqlNode> nodes = new ArrayList<>(selected.size());
        Class<?>[] types = new Class<?>[selected.size()];
        List<String> names = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            IGetter<?, ?> column = selected.get(i);
            ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
            nodes.add(SqlNodes.alias(provider.resolveNode(ctx), "c" + (i + 1)));
            types[i] = valueType(column);
            names.add((column instanceof Expr<?> expr && expr.alias() == null) ? null : provider.propertyName());
        }
        Class<?>[] parameterTypes = null;
        Class<?> record = RecordOrderVerifier.constructedRecord(mapper);
        if (record != null) {
            RecordOrderVerifier.verify(record, names);
            parameterTypes = RecordOrderVerifier.parameterTypes(record);
        }
        return Projection.mapped(nodes, new PositionalRowMapper<>(types, parameterTypes, factory),
                hasAggregate(selected), ctx);
    }

    private static SqlNode node(ColumnAlias alias) {
        return SqlNodes.column(alias.getTableAlias(), alias.getColumnName());
    }

    private static boolean hasAggregate(List<IGetter<?, ?>> selected) {
        for (IGetter<?, ?> column : selected) {
            if (column instanceof Expr<?> expr && expr.isAggregate()) {
                return true;
            }
        }
        return false;
    }

    static Class<?> valueType(IGetter<?, ?> column) {
        if (column instanceof Expr<?> expr) {
            return expr.resultType();
        }
        return LambdaUtils.getReturnType(column);
    }

    // ===== 内部辅助 =====

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
