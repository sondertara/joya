package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.Expr;
import com.sondertara.joya.core.query.TableScope;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import org.jspecify.annotations.Nullable;

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
 * 投影计划构建：把「挑哪些列 + 装进什么类型」通过 {@link TableScope} 解析为 SELECT AST 节点列表与行映射器。
 */
final class Projections {

    private record AutoProperty(String property, @Nullable String declaredColumn) {
    }

    /** DTO/Record 自动推列的属性及注解描述缓存，避免每次查询反射扫描类继承树与持久化注解。 */
    private static final ClassValue<List<AutoProperty>> AUTO_PROPERTIES = new ClassValue<>() {
        @Override
        protected List<AutoProperty> computeValue(Class<?> type) {
            List<Field> fields = extractProperties(type);
            List<AutoProperty> props = new ArrayList<>(fields.size());
            for (Field field : fields) {
                props.add(new AutoProperty(
                        field.getName(),
                        PersistenceAnnotations.findColumnName(field).orElse(null)));
            }
            return List.copyOf(props);
        }
    };

    private Projections() {
    }

    static <V> Projection<V> auto(Class<V> type, TableScope scope) {
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
        Collection<Class<?>> sources = scope.registeredEntities();
        if (sources.contains(type)) {
            return entity(type, scope);
        }
        if (scope.hasJoins()) {
            throw new IllegalStateException("Cannot auto-project non-entity type " + type.getSimpleName()
                    + " across joined tables without explicit select(...); use select(...).list("
                    + type.getSimpleName() + "::new) or select(...).list(" + type.getSimpleName() + ".class)");
        }
        List<AutoProperty> autoProps = AUTO_PROPERTIES.get(type);
        List<SqlNode> nodes = new ArrayList<>(autoProps.size());
        Map<String, SqlDataUtils.ColumnReader> readerOverrides = null;
        for (AutoProperty prop : autoProps) {
            TableScope.ResolvedColumn match = scope.findColumn(scope.rootType(), prop.property(), prop.declaredColumn());
            if (match == null) {
                throw new IllegalStateException("Property '" + prop.property() + "' of " + type.getSimpleName()
                        + " matches no column of " + scope.rootType().getSimpleName()
                        + "; use select(...).list(" + type.getSimpleName() + "::new)");
            }
            nodes.add(SqlNodes.alias(match.toNode(), prop.property()));
            if (match.converter() != null) {
                if (readerOverrides == null) {
                    readerOverrides = new LinkedHashMap<>();
                }
                readerOverrides.put(prop.property(), ColumnValues.readerFor(null, match.converter()));
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No mappable property found on " + type.getName());
        }
        return Projection.typed(nodes, kind, type,
                readerOverrides == null ? Map.of() : readerOverrides, false, scope);
    }

    /** 实体全部列（按实体元数据映射）。 */
    static <V> Projection<V> entity(Class<V> type, TableScope scope) {
        List<TableScope.ResolvedColumn> allColumns = scope.getAllColumns(type);
        List<SqlNode> nodes = new ArrayList<>(allColumns.size());
        for (TableScope.ResolvedColumn column : allColumns) {
            nodes.add(column.toNode());
        }
        return Projection.typed(nodes, ResultType.BEAN, type, false, scope);
    }

    static <V> Projection<V> named(List<IGetter<?, ?>> selected, Class<V> type, TableScope scope) {
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
        Map<String, SqlDataUtils.ColumnReader> readerOverrides = null;
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
            nodes.add(SqlNodes.alias(provider.resolveNode(scope), label));
            ColumnConverter conv = resolveConverter(column, provider, scope);
            if (conv != null) {
                if (readerOverrides == null) {
                    readerOverrides = new LinkedHashMap<>();
                }
                readerOverrides.put(label, ColumnValues.readerFor(null, conv));
            }
        }
        return Projection.typed(nodes, kind, type,
                readerOverrides == null ? Map.of() : readerOverrides, hasAggregate(selected), scope);
    }

    static <A> Projection<A> single(IGetter<?, ?> column, TableScope scope) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        Class<?> type = provider.valueType() != null ? provider.valueType() : valueType(column);
        ColumnConverter converter = resolveConverter(column, provider, scope);
        return Projection.mapped(
                List.of(SqlNodes.alias(provider.resolveNode(scope), "c1")),
                ColumnValues.singleMapper(type, converter),
                column instanceof Expr<?> expr && expr.isAggregate(),
                scope);
    }

    static <V> Projection<V> positional(List<IGetter<?, ?>> selected, Function<Object[], V> factory, Object mapper,
                                        TableScope scope) {
        List<SqlNode> nodes = new ArrayList<>(selected.size());
        Class<?>[] types = new Class<?>[selected.size()];
        ColumnConverter[] converters = null;
        List<String> names = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            IGetter<?, ?> column = selected.get(i);
            ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
            nodes.add(SqlNodes.alias(provider.resolveNode(scope), "c" + (i + 1)));
            types[i] = provider.valueType() != null ? provider.valueType() : valueType(column);
            ColumnConverter conv = resolveConverter(column, provider, scope);
            if (conv != null) {
                if (converters == null) {
                    converters = new ColumnConverter[selected.size()];
                }
                converters[i] = conv;
            }
            names.add((column instanceof Expr<?> expr && expr.alias() == null) ? null : provider.propertyName());
        }
        Class<?>[] parameterTypes = null;
        Class<?> record = RecordOrderVerifier.constructedRecord(mapper);
        if (record != null) {
            RecordOrderVerifier.verify(record, names);
            parameterTypes = RecordOrderVerifier.parameterTypes(record);
        }
        return Projection.mapped(nodes, new PositionalRowMapper<>(types, parameterTypes, converters, factory),
                hasAggregate(selected), scope);
    }

    private static @Nullable ColumnConverter resolveConverter(IGetter<?, ?> column,
                                                              ColumnMetaProvider provider,
                                                              TableScope scope) {
        if (column instanceof Expr.AggregateExpr<?> agg) {
            if (agg.column() != null
                    && ("MAX".equalsIgnoreCase(agg.fn()) || "MIN".equalsIgnoreCase(agg.fn()))) {
                return agg.column().resolveColumn(scope).converter();
            }
            return null;
        }
        if (column instanceof Expr<?> expr && !(expr instanceof Expr.ColumnExpr<?>)) {
            return null;
        }
        return provider.resolveColumn(scope).converter();
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
        return LambdaUtils.resolve(column).returnType();
    }

    private static List<Field> extractProperties(Class<?> type) {
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
