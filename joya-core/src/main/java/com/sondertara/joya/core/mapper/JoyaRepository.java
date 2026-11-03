package com.sondertara.joya.core.mapper;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.utils.TypeUtils;

import java.util.Objects;

/**
 * {@link JoyaMapper} 标准基类 —— 既可直接实例化，也可由业务 {@code XxxRepository} 继承扩展。
 *
 * <pre>{@code
 * public class UserRepository extends JoyaRepository<User> {
 *     public UserRepository(DataOps db) {
 *         super(db);
 *     }
 *
 *     public List<User> findActiveByDept(Long deptId) {
 *         return query().where(w -> w.eq(User::getDeptId, deptId)).list();
 *     }
 * }
 * }</pre>
 *
 * @param <T> 实体类型
 */
public class JoyaRepository<T> implements JoyaMapper<T> {

    private final DataOps db;
    private final Class<T> entityClass;

    public JoyaRepository(DataOps db, Class<T> entityClass) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass must not be null");
    }

    @SuppressWarnings("unchecked")
    protected JoyaRepository(DataOps db) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        Class<?> resolved = TypeUtils.resolveTypeArgument(getClass(), JoyaMapper.class);
        if (resolved == null || resolved == Object.class) {
            throw new IllegalStateException(
                    "Cannot resolve entity type parameter <T> from " + getClass().getName()
                            + "; specify type parameter or call super(db, entityClass)");
        }
        this.entityClass = (Class<T>) resolved;
    }

    @Override
    public DataOps db() {
        return db;
    }

    @Override
    public Class<T> entityClass() {
        return entityClass;
    }
}
