package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.resultset.mapper.DefaultRowMapperFactory;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.tx.TxCallback;
import com.sondertara.joya.core.tx.TxConsumer;
import com.sondertara.joya.core.tx.TxEngine;
import com.sondertara.joya.core.tx.TxOptions;
import com.sondertara.joya.core.tx.TxStarter;

import javax.sql.DataSource;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    private final ExecutionKernel kernel;
    private final TxEngine txEngine;
    private final EntityLifecycle entityLifecycle;
    private final DataOpsSupport dataOps;

    public JoyaClient(DataSource dataSource) {
        this(builder(dataSource));
    }

    private JoyaClient(Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null");
        ConnectionProvider effectiveConnections = builder.connectionProvider != null
                ? builder.connectionProvider
                : new DataSourceConnectionProvider(
                        builder.dataSource, builder.settings.executionOptions().queryTimeoutSeconds());
        ConnectionProvider unwrappedConnections = effectiveConnections.unwrap();
        DataSource providerDataSource = unwrappedConnections.originDataSource();
        if (providerDataSource != null && !unwrappedConnections.matchesDataSource(builder.dataSource)) {
            throw new IllegalArgumentException(
                    "ConnectionProvider is bound to a different DataSource than the JoyaClient; "
                            + "dialect probing and SQL execution would target different databases. "
                            + "Pass the same DataSource to builder(...) and the ConnectionProvider.");
        }
        DialectStrategy effectiveDialect = builder.dialectStrategy != null
                ? builder.dialectStrategy
                : (providerDataSource != null
                        ? DialectStrategy.cached(providerDataSource)
                        : DialectStrategy.cachedFromProvider());
        this.txEngine = builder.txEngine != null ? builder.txEngine : TxEngine.JDBC;
        if (effectiveConnections.isExternallyManaged() && this.txEngine == TxEngine.JDBC) {
            throw new IllegalArgumentException(
                    "Externally managed ConnectionProvider ("
                            + effectiveConnections.unwrap().getClass().getSimpleName()
                            + ") cannot be paired with TxEngine.JDBC; configure a matching TxEngine (e.g. SpringTxEngine)");
        }
        this.txEngine.validateConfiguration(builder.dataSource, effectiveConnections);
        this.entityLifecycle = builder.entityListeners.isEmpty()
                ? EntityLifecycle.EMPTY
                : new EntityLifecycle(builder.entityListeners);
        this.kernel = new ExecutionKernel(new ExecutionKernel.Config(
                builder.rewriters,
                builder.executionListeners,
                effectiveConnections,
                effectiveDialect,
                builder.settings.executionOptions(),
                builder.settings.namingPolicy(),
                builder.rowMapperFactory,
                builder.exceptionTranslator,
                builder.executorFactory));
        this.dataOps = DataOpsSupport.concurrent(this, kernel, entityLifecycle);
    }

    /** 快捷创建客户端。 */
    public static JoyaClient create(DataSource dataSource) {
        return builder(dataSource).build();
    }

    /** 创建客户端构建器（支持注册 SQL 结构改写器、执行监听器、连接提供者、事务引擎、方言策略与客户端配置）。 */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    // ===== DataOps 核心入口 =====

    @Override
    public <T> Query<T> from(Class<T> entityType) {
        return dataOps.from(entityType);
    }

    @Override
    public <T> EntityOps<T> of(Class<T> entityType) {
        return dataOps.of(entityType);
    }

    @Override
    public SqlOps sql(String sql, Object... args) {
        return dataOps.sql(sql, args);
    }

    @Override
    public SqlOps sql(SqlStatement statement) {
        return dataOps.sql(statement);
    }

    @Override
    public <M> M mapper(Class<M> mapperType) {
        return dataOps.mapper(mapperType);
    }

    // ===== 显式事务入口 =====

    /**
     * 开启事务配置与执行向导 {@link TxStarter}（需自定义隔离级别、只读、超时或死锁重试时使用）。
     */
    public TxStarter tx() {
        return new TxStarter(kernel, txEngine, TxOptions.DEFAULT, entityLifecycle);
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

    // ===== 底层逃生舱与配置访问 =====

    /** 执行 SQL 脚本流。 */
    @Override
    public void script(Reader reader) {
        dataOps.script(reader);
    }

    /**
     * 在连接回调中执行底层 JDBC 操作（每次借还一条连接，不经过改写器/观测管线）。
     *
     * <p>连接来源由 {@code ConnectionProvider} 决定：自动提交客户端使用独立连接；Spring/JPA 环境下
     * {@code DataSourceUtils} 可能返回外层 {@code @Transactional} 绑定的连接。事务控制方法
     * （{@code commit / rollback / setAutoCommit / setSavepoint ...}）受连接能力位保护，禁止在此直接调用；
     * 需要事务请使用 {@link com.sondertara.joya.core.tx.Tx}。
     */
    @Override
    public <T> T withConnection(ConnectionCallback<T> action) {
        return dataOps.withConnection(action);
    }

    /** 获取当前客户端的实例级配置 {@link JoyaSettings}。 */
    @Override
    public JoyaSettings settings() {
        return dataOps.settings();
    }

    @Override
    public DbType dbType() {
        return dataOps.dbType();
    }

    // ===== 构建器 =====

    public static final class Builder {
        private final DataSource dataSource;
        private final List<SqlRewriter> rewriters = new ArrayList<>();
        private final List<SqlExecutionListener> executionListeners = new ArrayList<>();
        private final List<EntityListener> entityListeners = new ArrayList<>();
        private ConnectionProvider connectionProvider;
        private TxEngine txEngine;
        private DialectStrategy dialectStrategy;
        private JoyaSettings settings = JoyaSettings.DEFAULT;
        private RowMapperFactory rowMapperFactory = DefaultRowMapperFactory.INSTANCE;
        private SqlExceptionTranslator exceptionTranslator = SqlExceptionTranslator.DEFAULT;
        private Executor.Factory executorFactory;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /** 追加 SQL 结构改写器（AST 级，推荐用于租户/软删/审计等结构变更）。 */
        public Builder rewriter(SqlRewriter rewriter) {
            Objects.requireNonNull(rewriter, "rewriter must not be null");
            this.rewriters.add(rewriter);
            return this;
        }

        /** 批量追加 SQL 结构改写器。 */
        public Builder rewriters(List<SqlRewriter> list) {
            if (list != null) {
                this.rewriters.addAll(list);
            }
            return this;
        }

        /** 追加 SQL 执行观测监听器（日志/指标/Tracing）。 */
        public Builder executionListener(SqlExecutionListener listener) {
            this.executionListeners.add(Objects.requireNonNull(listener, "executionListener must not be null"));
            return this;
        }

        /** 批量追加 SQL 执行观测监听器。 */
        public Builder executionListeners(List<SqlExecutionListener> listeners) {
            if (listeners != null) {
                this.executionListeners.addAll(listeners);
            }
            return this;
        }

        /** 自定义行映射器工厂。 */
        public Builder rowMapperFactory(RowMapperFactory factory) {
            this.rowMapperFactory = Objects.requireNonNull(factory, "rowMapperFactory must not be null");
            return this;
        }

        /** 自定义执行器工厂（SQL 执行端口的替换点；事务视图会用事务连接来源重新调用该工厂）。 */
        public Builder executorFactory(Executor.Factory factory) {
            this.executorFactory = Objects.requireNonNull(factory, "executorFactory must not be null");
            return this;
        }

        /** 自定义 SQL 异常翻译器。 */
        public Builder exceptionTranslator(SqlExceptionTranslator translator) {
            this.exceptionTranslator = Objects.requireNonNull(translator, "exceptionTranslator must not be null");
            return this;
        }

        /** 注册全局实体生命周期监听器（审计、日志等）。 */
        public Builder entityListener(EntityListener listener) {
            this.entityListeners.add(Objects.requireNonNull(listener, "entityListener must not be null"));
            return this;
        }

        /** 批量注册全局实体生命周期监听器。 */
        public Builder entityListeners(List<EntityListener> listeners) {
            if (listeners != null) {
                this.entityListeners.addAll(listeners);
            }
            return this;
        }

        /** 设置默认查询超时秒数（{@code <= 0} 表示不限制；直接合并至当前 {@link JoyaSettings}）。 */
        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.settings = this.settings.withQueryTimeoutSeconds(queryTimeoutSeconds);
            return this;
        }

        /**
         * 自定义连接提供者（例如 Spring 环境的 {@code SpringConnectionProvider}）。
         *
         * <p>若自定义提供者的 {@link ConnectionProvider#originDataSource()} 返回 {@code null} 且未显式指定
         * {@link #dialectStrategy(DialectStrategy)}，则客户端默认采用 {@link DialectStrategy#cachedFromProvider()}
         * （首次执行时经该提供者探测一次并缓存方言）；对于会在运行期跨异构数据库动态切换路由的提供者，
         * 必须显式配置 {@code .dialectStrategy(DialectStrategy.perConnection())}。
         */
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
            return new JoyaClient(this);
        }
    }
}
