package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.mapper.JdkMapperProxy;
import com.sondertara.joya.core.query.dsl.Query;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DataOps} 门面公共装配委托（框架内部组件，供 {@link JoyaClient} 与 {@link com.sondertara.joya.core.tx.TxImpl} 共用）：
 * <ul>
 *   <li>客户端模式（{@link #concurrent}）：多线程安全缓存（{@link ConcurrentHashMap}），无线程限制。</li>
 *   <li>事务模式（{@link #singleThread}）：每次入口调用前执行所有者线程守卫（不变量 I9），使用单线程惰性 {@link HashMap}。</li>
 * </ul>
 */
public final class DataOpsSupport {

    private static final Runnable NOOP = () -> {
    };

    private final DataOps owner;
    private final ExecutionKernel kernel;
    private final EntityLifecycle entityLifecycle;
    private final JoyaSettings settings;
    private final boolean concurrent;
    private final Runnable accessGuard;
    private Map<Class<?>, EntityOps<?>> opsCache;
    private Map<Class<?>, Object> mapperCache;

    private DataOpsSupport(DataOps owner,
                           ExecutionKernel kernel,
                           EntityLifecycle entityLifecycle,
                           boolean concurrent,
                           Runnable accessGuard) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.entityLifecycle = entityLifecycle == null ? EntityLifecycle.EMPTY : entityLifecycle;
        this.settings = JoyaSettings.of(kernel.executionOptions(), kernel.namingPolicy());
        this.concurrent = concurrent;
        this.accessGuard = accessGuard == null ? NOOP : accessGuard;
        if (concurrent) {
            this.opsCache = new ConcurrentHashMap<>();
            this.mapperCache = new ConcurrentHashMap<>();
        }
    }

    /** 创建多线程共享的客户端门面委托。 */
    public static DataOpsSupport concurrent(DataOps owner, ExecutionKernel kernel, EntityLifecycle entityLifecycle) {
        return new DataOpsSupport(owner, kernel, entityLifecycle, true, NOOP);
    }

    /** 创建单线程独占的事务门面委托（所有入口调用前先触发 {@code accessGuard}）。 */
    public static DataOpsSupport singleThread(DataOps owner,
                                              ExecutionKernel kernel,
                                              EntityLifecycle entityLifecycle,
                                              Runnable accessGuard) {
        return new DataOpsSupport(owner, kernel, entityLifecycle, false, accessGuard);
    }

    public <T> Query<T> from(Class<T> entityType) {
        accessGuard.run();
        return new Query<>(kernel, entityType);
    }

    @SuppressWarnings("unchecked")
    public <T> EntityOps<T> of(Class<T> entityType) {
        accessGuard.run();
        Objects.requireNonNull(entityType, "entityType must not be null");
        if (!concurrent && opsCache == null) {
            opsCache = new HashMap<>(4);
        }
        return (EntityOps<T>) opsCache.computeIfAbsent(entityType,
                type -> EntityOps.of(kernel, (Class<Object>) type, entityLifecycle));
    }

    public SqlOps sql(String sql, Object... args) {
        accessGuard.run();
        return new SqlOps(kernel, sql, args);
    }

    public SqlOps sql(SqlStatement statement) {
        accessGuard.run();
        return new SqlOps(kernel, statement);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M> M mapper(Class<M> mapperType) {
        accessGuard.run();
        Objects.requireNonNull(mapperType, "mapperType must not be null");
        if (!mapperType.isInterface()) {
            throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
        }
        if (!concurrent && mapperCache == null) {
            mapperCache = new HashMap<>(4);
        }
        return (M) mapperCache.computeIfAbsent(mapperType, cls -> new JdkMapperProxy(owner).create((Class) cls));
    }

    public <T> T withConnection(ConnectionCallback<T> action) {
        accessGuard.run();
        return kernel.withConnection(action);
    }

    public void script(Reader reader) {
        accessGuard.run();
        kernel.runScript(reader);
    }

    public JoyaSettings settings() {
        accessGuard.run();
        return settings;
    }

    public DbType dbType() {
        accessGuard.run();
        return kernel.dialect().dbType();
    }
}
