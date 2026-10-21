package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.dao.support.EntityBasedBoundSql;
import com.sondertara.joya.core.jdbc.dao.support.JPAQueryParser;
import com.sondertara.joya.core.jdbc.dao.support.NativeQueryParser;
import com.sondertara.joya.core.jdbc.support.annotation.Query;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.utils.Assert;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.sondertara.joya.utils.SqlFormatUtils.POSITIONAL_OR_INDEXED_PARAMETER;

/**
 * @author huangxiaohu
 */
public class JoyaExecutorSupport {

    private final DataOps joyaClient;
    private final Class<?> entityClass;

    public JoyaExecutorSupport(DataOps joyaClient, Class<?> entityClass) {
        this.joyaClient = joyaClient;
        this.entityClass = entityClass;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object execute(final Method method, Object[] args) {
        Object[] actualArgs = args == null ? new Object[0] : args;
        EntityBasedBoundSql boundSql;
        Query annotation = method.getAnnotation(Query.class);
        if (annotation != null) {
            boundSql = new NativeQueryParser(entityClass, method).parse();
        } else {
            boundSql = new JPAQueryParser<>(entityClass, method, actualArgs).parse();
        }
        String sqlString = boundSql.getSqlString();
        Type returnType = boundSql.returnType();
        Class<?> aClass = TypeUtils.getClass(returnType);
        List<Object> list = new ArrayList<>();
        for (ParameterBinding binding : boundSql.parameterBinding()) {
            if (binding instanceof ConstantParameterBinding constantBinding) {
                list.add(constantBinding.getValue());
                continue;
            }
            int position = binding.getRequiredPosition();
            if (position > actualArgs.length) {
                throw new IllegalArgumentException(Strings.format(
                        "Parameter position[{}] not match args index for method[{}]", position - 1, method));
            }
            Object prepared = binding.prepare(actualArgs[position - 1]);
            list.add(prepared);
        }

        sqlString = sqlString.replaceAll(POSITIONAL_OR_INDEXED_PARAMETER, "?");

        boolean query = boundSql.isQuery();
        Object[] parameters = list.toArray();
        SqlOps sqlOps = createSqlOps(sqlString, parameters, query);

        if (!query) {
            int affected = sqlOps.update();
            if (aClass == void.class || aClass == Void.class) {
                return null;
            }
            if (aClass == boolean.class || aClass == Boolean.class) {
                return affected > 0;
            }
            if (aClass == long.class || aClass == Long.class) {
                return (long) affected;
            }
            return affected;
        }

        // boolean return (e.g. existsBy...)
        if (aClass == boolean.class || aClass == Boolean.class) {
            Object raw = sqlOps.value(Object.class).orElse(null);
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof Number n) {
                return n.longValue() > 0;
            }
            return raw != null;
        }

        // return List / Collection
        if (Collection.class.isAssignableFrom(aClass)) {
            Type argument = TypeUtils.getTypeArgument(returnType);
            Class<?> returnClass = argument != null ? TypeUtils.getClass(argument) : entityClass;
            return sqlOps.list(returnClass);
        }

        // return Optional<T>
        if (Optional.class.isAssignableFrom(aClass)) {
            Type argument = TypeUtils.getTypeArgument(returnType);
            Class<?> returnClass = argument != null ? TypeUtils.getClass(argument) : entityClass;
            if (SqlOps.isScalarType(returnClass)) {
                return sqlOps.value(returnClass);
            }
            return sqlOps.one(returnClass);
        }

        // single scalar value (Number, primitives, String, Date, Temporal, etc.)
        if (SqlOps.isScalarType(aClass)) {
            Object val = sqlOps.value((Class) aClass).orElse(null);
            if (val == null && aClass.isPrimitive()) {
                return defaultPrimitive(aClass);
            }
            return val;
        }

        // return custom DTO or entity
        return sqlOps.one(aClass).orElse(null);
    }

    private SqlOps createSqlOps(String sql, Object[] parameters, boolean query) {
        try {
            EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(entityClass);
            Operation op = query
                    ? Operation.SELECT
                    : (sql.trim().toLowerCase().startsWith("delete") ? Operation.DELETE : Operation.UPDATE);
            StatementMetadata metadata = StatementMetadata.builder(op)
                    .table(TableRef.of(meta.qualifiedTableName()))
                    .entityType(entityClass)
                    .build();
            SqlStatement stmt = (query ? SqlStatement.select(sql, parameters) : SqlStatement.update(sql, parameters))
                    .withMetadata(metadata);
            return joyaClient.sql(stmt);
        } catch (Exception ignored) {
            return joyaClient.sql(sql, parameters);
        }
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return null;
    }

    public static class ParameterBinding {

        private final @Nullable String name;
        private final @Nullable String expression;
        private final @Nullable Integer position;

        public ParameterBinding(Integer position) {
            this(null, position, null);
        }

        ParameterBinding(@Nullable String name, @Nullable Integer position, @Nullable String expression) {
            if (name == null) {
                Assert.notNull(position, "Position must not be null!");
            }
            if (position == null) {
                Assert.notNull(name, "Name must not be null!");
            }
            this.name = name;
            this.position = position;
            this.expression = expression;
        }

        public static ParameterBinding constant(@Nullable Object value) {
            return new ConstantParameterBinding(value);
        }

        public static ParameterBinding like(int position, boolean prefixWildcard, boolean suffixWildcard) {
            return new LikeParameterBinding(position, prefixWildcard, suffixWildcard);
        }

        boolean hasName(@Nullable String name) {
            return this.position == null && this.name != null && this.name.equals(name);
        }

        boolean hasPosition(@Nullable Integer position) {
            return position != null && this.name == null && position.equals(this.position);
        }

        @Nullable
        public String getName() {
            return name;
        }

        String getRequiredName() throws IllegalStateException {
            String name = getName();
            if (name != null) {
                return name;
            }
            throw new IllegalStateException(String.format("Required name for %s not available!", this));
        }

        @Nullable
        Integer getPosition() {
            return position;
        }

        public int getRequiredPosition() throws IllegalStateException {
            Integer position = getPosition();
            if (position != null) {
                return position;
            }
            throw new IllegalStateException(String.format("Required position for %s not available!", this));
        }

        public boolean isExpression() {
            return this.expression != null;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            ParameterBinding that = (ParameterBinding) object;
            return Objects.equals(name, that.name) && Objects.equals(expression, that.expression) && Objects.equals(position, that.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, expression, position) + 17;
        }

        @Override
        public String toString() {
            return String.format("ParameterBinding [name: %s, position: %d, expression: %s]", getName(), getPosition(),
                    getExpression());
        }

        @Nullable
        public Object prepare(@Nullable Object valueToBind) {
            return valueToBind;
        }

        @Nullable
        public String getExpression() {
            return expression;
        }
    }

    public static class ConstantParameterBinding extends ParameterBinding {
        private final @Nullable Object value;

        public ConstantParameterBinding(@Nullable Object value) {
            super("__constant__", null, null);
            this.value = value;
        }

        @Nullable
        public Object getValue() {
            return value;
        }

        @Override
        public @Nullable Object prepare(@Nullable Object valueToBind) {
            return value;
        }
    }

    public static class LikeParameterBinding extends ParameterBinding {
        private final boolean prefixWildcard;
        private final boolean suffixWildcard;

        public LikeParameterBinding(Integer position, boolean prefixWildcard, boolean suffixWildcard) {
            super(position);
            this.prefixWildcard = prefixWildcard;
            this.suffixWildcard = suffixWildcard;
        }

        @Override
        public @Nullable Object prepare(@Nullable Object valueToBind) {
            if (valueToBind == null) {
                return null;
            }
            String s = valueToBind.toString();
            StringBuilder sb = new StringBuilder(s.length() + 2);
            if (prefixWildcard && !s.startsWith("%")) {
                sb.append('%');
            }
            sb.append(s);
            if (suffixWildcard && !s.endsWith("%")) {
                sb.append('%');
            }
            return sb.toString();
        }
    }

    public static class InParameterBinding extends ParameterBinding {

        public InParameterBinding(Integer position) {
            super(position);
        }

        InParameterBinding(@Nullable String name, @Nullable Integer position, @Nullable String expression) {
            super(name, position, expression);
        }

        @Override
        public @Nullable Object prepare(@Nullable Object valueToBind) {
            if (valueToBind == null) {
                return null;
            }
            if (valueToBind.getClass().isArray()) {
                int length = Array.getLength(valueToBind);
                Collection<Object> result = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    result.add(Array.get(valueToBind, i));
                }
                return result;
            } else if (valueToBind instanceof Collection<?> col) {
                return new ArrayList<>(col);
            }
            return valueToBind;
        }
    }
}
