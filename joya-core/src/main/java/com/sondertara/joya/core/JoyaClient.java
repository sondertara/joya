package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.tx.TxEngine;
import com.sondertara.joya.core.proxy.JdkMapperProxy;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.tx.JdbcTxEngine;
import com.sondertara.joya.core.tx.TxCallback;
import com.sondertara.joya.core.tx.TxConsumer;
import com.sondertara.joya.core.tx.TxStarter;

import javax.sql.DataSource;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joya 统一客户端门面 —— 实现 {@link DataOps}（1 {@code JoyaClient} = 1 {@link DataSource}）：
 * <ul>
 *   <li>{@link #from(Class)}：统一查询入口（单表、联表、部分列、DTO/Record 投影、分组聚合、分页全部由此进入）。</li>
 *   <li>{@link #of(Class)}：进入类型化实体域 {@link EntityOps}（实体级 CRUD、列级变更链与按主键快捷读）。</li>
 *   <li>{@link #sql(String, Object...)} / {@link #sql(SqlStatement)}：进入参数化 SQL 链 {@link SqlOps}。</li>
 *   <li>{@link #mapper(Class)}：获取绑定当前客户端的 {@link JoyaMapper} 代理实例（实例级缓存，无需全局扫描）。</li>
 * </ul>
 *
 * <p>事务入口为 {@link #tx()}、{@link #tx(TxConsumer)}、{@link #txCall(TxCallback)}，产出显式事务值
 * {@link com.sondertara.joya.core.tx.Tx}；{@code Tx} 同样实现 {@link DataOps}，事务内外书写风格完全一致。
 */
public final class JoyaClient implements DataOps {

    private final JoyaTemplate template;
    private final TxEngine txEngine;
    private final Map<Class<?>, Object> mapperCache = new ConcurrentHashMap<>();

    public JoyaClient(DataSource dataSource) {
        this(dataSource, List.of());
    }

    public JoyaClient(DataSource dataSource, List<SqlInterceptor> interceptors) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.template = new JoyaTemplate(dataSource, interceptors == null ? List.of() : interceptors);
        this.txEngine = JdbcTxEngine.INSTANCE;
    }

    public JoyaClient(JoyaTemplate template) {
        this(template, JdbcTxEngine.INSTANCE);
    }

    public JoyaClient(JoyaTemplate template, TxEngine txEngine) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.txEngine = Objects.requireNonNull(txEngine, "txEngine must not be null");
    }

    /** 快捷创建客户端。 */
    public static JoyaClient create(DataSource dataSource) {
        return new JoyaClient(dataSource);
    }

    /** 创建客户端构建器（支持注册 SQL 拦截器、连接提供者、事务引擎、方言策略与客户端配置）。 */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    // ===== DataOps 核心入口 =====

    @Override
    public <T> Query<T> from(Class<T> entityType) {
        return new Query<>(template, entityType);
    }

    @Override
    public <T> EntityOps<T> of(Class<T> entityType) {
        return new EntityOps<>(template, entityType);
    }

    @Override
    public SqlOps sql(String sql, Object... args) {
        return new SqlOps(template, sql, args);
    }

    @Override
    public SqlOps sql(SqlStatement statement) {
        return new SqlOps(template, statement);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M extends JoyaMapper> M mapper(Class<M> mapperType) {
        Objects.requireNonNull(mapperType, "mapperType must not be null");
        if (!mapperType.isInterface()) {
            throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
        }
        return (M) mapperCache.computeIfAbsent(mapperType, cls -> new JdkMapperProxy(this).create((Class) cls));
    }

    // ===== 显式事务入口 =====

    /**
     * 开启事务配置与执行向导 {@link TxStarter}（需自定义隔离级别、只读、超时或死锁重试时使用）。
     */
    public TxStarter tx() {
        return new TxStarter(template, txEngine);
    }

    /**
     * 使用默认事务配置执行无返回值事务块（正常结束自动 commit，抛异常自动 rollback）。
     */
    public void tx(TxConsumer body) {
        tx().run(body);
    }

    /**
     * 使用默认事务配置执行带返回值事务块（正常结束自动 commit，抛异常自动 rollback）。
     */
    public <R> R txCall(TxCallback<R> body) {
        return tx().call(body);
    }

    // ===== Mapper 代理入口 =====

    /**
     * 获取自定义 {@link JoyaMapper} 子接口的动态代理（等价于 {@link #mapper(Class)}）。
     */
    @SuppressWarnings("rawtypes")
    public <M extends JoyaMapper> M createMapper(Class<M> mapperType) {
        return mapper(mapperType);
    }

    /**
     * 创建针对指定实体的标准 {@link JoyaMapper} 实例。
     */
    public <T> JoyaMapper<T> buildMapper(Class<T> entityType) {
        return new JdkMapperProxy(this).build(entityType);
    }

    // ===== 底层逃生舱与配置访问 =====

    /** 执行 SQL 脚本流。 */
    public void script(Reader reader) {
        template.runScript(reader);
    }

    /** 在连接回调中执行底层 JDBC 操作。 */
    public <T> T withConnection(ConnectionCallback<T> action) {
        return template.withConnection(action);
    }

    /** 获取底层执行内核 {@link JoyaTemplate}。 */
    public JoyaTemplate template() {
        return template;
    }

    /** 获取当前客户端绑定的 {@link DataSource}。 */
    public DataSource dataSource() {
        return template.dataSource();
    }

    /** 获取当前客户端的实例级配置 {@link JoyaSettings}。 */
    public JoyaSettings settings() {
        return template.settings();
    }

    /** 获取当前客户端使用的事务引擎 {@link TxEngine}。 */
    public TxEngine txEngine() {
        return txEngine;
    }

    // ===== 构建器 =====

    public static final class Builder {
        private final DataSource dataSource;
        private final List<SqlInterceptor> interceptors = new ArrayList<>();
        private int queryTimeoutSeconds = 0;
        private ConnectionProvider connectionProvider;
        private TxEngine txEngine;
        private DialectStrategy dialectStrategy;
        private JoyaSettings settings = JoyaSettings.DEFAULT;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /** 追加单个 SQL 拦截器。 */
        public Builder interceptor(SqlInterceptor interceptor) {
            Objects.requireNonNull(interceptor, "interceptor must not be null");
            this.interceptors.add(interceptor);
            return this;
        }

        /** 批量追加 SQL 拦截器。 */
        public Builder interceptors(List<SqlInterceptor> list) {
            if (list != null) {
                this.interceptors.addAll(list);
            }
            return this;
        }

        /** 设置默认查询超时秒数（{@code <= 0} 表示不限制）。 */
        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        /** 自定义连接提供者（例如 Spring 环境的 {@code SpringConnectionProvider}）。 */
        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
            return this;
        }

        /** 自定义事务引擎（例如 Spring 环境的 {@code SpringTxEngine}）。 */
        public Builder txEngine(TxEngine txEngine) {
            this.txEngine = Objects.requireNonNull(txEngine, "txEngine must not be null");
            return this;
        }

        /** 自定义方言解析策略。 */
        public Builder dialectStrategy(DialectStrategy dialectStrategy) {
            this.dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
            return this;
        }

        /** 显式指定固定数据库方言（零连接探测）。 */
        public Builder dialect(DbType dbType) {
            this.dialectStrategy = DialectStrategy.fixed(dbType);
            return this;
        }

        /** 设置实例级客户端配置。 */
        public Builder settings(JoyaSettings settings) {
            this.settings = Objects.requireNonNull(settings, "settings must not be null");
            return this;
        }

        public JoyaClient build() {
            JoyaSettings effectiveSettings = (settings.queryTimeoutSeconds() == 0 && queryTimeoutSeconds > 0)
                    ? settings.withQueryTimeoutSeconds(queryTimeoutSeconds)
                    : settings;
            ConnectionProvider effectiveConnections = connectionProvider != null
                    ? connectionProvider
                    : new DataSourceConnectionProvider(dataSource, effectiveSettings.queryTimeoutSeconds());
            DialectStrategy effectiveDialect = dialectStrategy != null
                    ? dialectStrategy
                    : DialectStrategy.cached(dataSource);
            TxEngine effectiveTxEngine = txEngine != null
                    ? txEngine
                    : JdbcTxEngine.INSTANCE;
            JoyaTemplate tpl = new JoyaTemplate(
                    dataSource,
                    interceptors,
                    effectiveConnections,
                    effectiveDialect,
                    effectiveSettings
            );
            return new JoyaClient(tpl, effectiveTxEngine);
        }
    }
}
