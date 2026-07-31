package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.common.base.Assert;
import com.sondertara.common.collection.ArrayUtils;
import com.sondertara.common.reflect.type.TypeUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.dao.support.EntityBasedBoundSql;
import com.sondertara.joya.core.jdbc.dao.support.JPAQueryParser;
import com.sondertara.joya.core.jdbc.dao.support.NativeQueryParser;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.support.annotation.Query;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.sondertara.joya.utils.SqlFormatUtils.POSITIONAL_OR_INDEXED_PARAMETER;


/**
 * @author huangxiaohu
 */
public class JoyaExecutorSupport {


    private final JoyaJdbc joyaJdbc;

    private final Class<?> entityClass;

    public JoyaExecutorSupport(JoyaJdbc joyaJdbc, Class<?> entityClass) {
        this.joyaJdbc = joyaJdbc;
        this.entityClass = entityClass;
    }

    public Object execute(final Method method, Object[] args) {
        EntityBasedBoundSql boundSql = null;
        Query annotation = method.getAnnotation(Query.class);
        if (annotation != null) {
            boundSql = new NativeQueryParser(entityClass, method).parse();
        } else {
            boundSql = new JPAQueryParser<>(entityClass, method).parse();
        }
        String sqlString = boundSql.getSqlString();
        Type returnType = boundSql.returnType();
        Class<?> aClass = TypeUtils.getClass(returnType);
        List<Object> list = new ArrayList<>();
        for (ParameterBinding binding : boundSql.parameterBinding()) {
            int position = binding.getRequiredPosition();
            if (position > args.length) {
                throw new IllegalArgumentException(StringUtils.format("Parameter position[{}] not match args index for method[{}]", position - 1, method));
            }
            list.add(args[position - 1]);
        }

        sqlString = sqlString.replaceAll(POSITIONAL_OR_INDEXED_PARAMETER, "?");

        boolean query = boundSql.isQuery();
        Object[] parameters = list.toArray();
        if (!query) {
            return joyaJdbc.execute(sqlString, parameters);
        }
        //return list
        if (List.class.isAssignableFrom(aClass)) {
            Type argument = TypeUtils.getTypeArgument(returnType);
            Class<?> returnClass = TypeUtils.getClass(argument);
            return joyaJdbc.queryList(sqlString, new BeanRowMapper<>(returnClass), parameters);
        }
        // single value
        if (Number.class.isAssignableFrom(aClass) || String.class.isAssignableFrom(aClass)) {
            return joyaJdbc.querySingleValue(sqlString, aClass, parameters);
        }
        // return custom object
        if (aClass != entityClass) {
            joyaJdbc.querySingleRow(sqlString, new BeanRowMapper<>(aClass), parameters);
        }
        //return the entity
        return joyaJdbc.querySingleRow(sqlString, new BeanRowMapper<>(entityClass), parameters);
    }


    public static class ParameterBinding {

        private final @Nullable String name;
        private final @Nullable String expression;
        private final @Nullable Integer position;

        /**
         * Creates a new {@link ParameterBinding} for the parameter with the given position.
         *
         * @param position must not be {@literal null}.
         */
        public ParameterBinding(Integer position) {
            this(null, position, null);
        }

        /**
         * Creates a new {@link ParameterBinding} for the parameter with the given name, position and expression
         * information. Either {@literal name} or {@literal position} must be not {@literal null}.
         *
         * @param name       of the parameter may be {@literal null}.
         * @param position   of the parameter may be {@literal null}.
         * @param expression the expression to apply to any value for this parameter.
         */
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

        /**
         * Returns whether the binding has the given name. Will always be {@literal false} in case the
         * {@link ParameterBinding} has been set up from a position.
         */
        boolean hasName(@Nullable String name) {
            return this.position == null && this.name != null && this.name.equals(name);
        }

        /**
         * Returns whether the binding has the given position. Will always be {@literal false} in case the
         * {@link ParameterBinding} has been set up from a name.
         */
        boolean hasPosition(@Nullable Integer position) {
            return position != null && this.name == null && position.equals(this.position);
        }

        /**
         * @return the name
         */
        @Nullable
        public String getName() {
            return name;
        }

        /**
         * @return the name
         * @throws IllegalStateException if the name is not available.
         * @since 2.0
         */
        String getRequiredName() throws IllegalStateException {

            String name = getName();

            if (name != null) {
                return name;
            }

            throw new IllegalStateException(String.format("Required name for %s not available!", this));
        }

        /**
         * @return the position
         */
        @Nullable
        Integer getPosition() {
            return position;
        }

        /**
         * @return the position
         * @throws IllegalStateException if the position is not available.
         * @since 2.0
         */
        public int getRequiredPosition() throws IllegalStateException {

            Integer position = getPosition();

            if (position != null) {
                return position;
            }

            throw new IllegalStateException(String.format("Required position for %s not available!", this));
        }

        /**
         * @return {@literal true} if this parameter binding is a synthetic SpEL expression.
         */
        public boolean isExpression() {
            return this.expression != null;
        }

        /*
         * (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */

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

        /*
         * (non-Javadoc)
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            return String.format("ParameterBinding [name: %s, position: %d, expression: %s]", getName(), getPosition(),
                    getExpression());
        }

        /**
         * @param valueToBind value to prepare
         */
        @Nullable
        public Object prepare(@Nullable Object valueToBind) {
            return valueToBind;
        }

        @Nullable
        public String getExpression() {
            return expression;
        }
    }


    class InParameterBinding extends ParameterBinding {

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
            if (ArrayUtils.isArray(valueToBind)) {
                int length = Array.getLength(valueToBind);
                Collection<Object> result = new ArrayList<>(length);

                for (int i = 0; i < length; i++) {
                    result.add(Array.get(valueToBind, i));
                }
                return result;
            } else if (Collection.class.isAssignableFrom(valueToBind.getClass())) {
                Collection<Object> result = new ArrayList<>();
                for (Object object : (Collection) valueToBind) {
                    result.add(object);
                }
                return result;
            }
            return valueToBind;
        }
    }


}
