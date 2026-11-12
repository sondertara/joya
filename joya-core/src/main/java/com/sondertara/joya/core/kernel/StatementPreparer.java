package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语句准备管线（单条与批量共用的唯一入口）：注入方言类型 → AST 结构改写（{@link SqlRewriter}）→ 单一渲染。
 *
 * <p>{@link ExecutionKernel} 只负责"准备好之后怎么执行"，所有结构改写语义都收敛在这里，
 * 因此查询、变更、原生 SQL 与批处理没有任何旁路。改写器按 {@code order()} 在首次执行时排序一次
 * （扩展点可能是容器启动期的循环依赖代理，构造期读取 {@code order()} 不可靠）。
 */
final class StatementPreparer {

    private final List<SqlRewriter> rewriters;
    private final DialectStrategy dialectStrategy;
    private final ConnectionProvider connections;
    private volatile List<SqlRewriter> orderedRewriters;

    StatementPreparer(List<SqlRewriter> rewriters,
                      DialectStrategy dialectStrategy,
                      ConnectionProvider connections) {
        this.rewriters = rewriters == null ? List.of() : List.copyOf(rewriters);
        this.dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
    }

    List<SqlRewriter> rewriters() {
        return rewriters;
    }

    boolean hasRewriters() {
        return !rewriters.isEmpty();
    }

    /** SELECT 单表查询最常用的根表别名；探测时同时覆盖「带别名」与「无别名」两种形态。 */
    private static final String PROBE_ROOT_ALIAS = "t0";

    /**
     * 只探测 {@code supports(ctx)}，不执行改写：用于判断优化路径（如原生批量 UPSERT）是否会被改写器影响。
     *
     * <p>逐个操作构建三种上下文（单条无别名、单条带别名、批量模板），任一改写器对任一操作声明支持
     * 即视为「可能生效」——保守判定，避免把「改写器只在其他操作/其他形态生效」误判为安全而绕过行级过滤。
     *
     * <p>约定：{@code supports()} 的判定应只依赖 operation / entityType / tables / dbType；
     * 若自定义改写器把 batch、rootAlias 或 attributes 作为必要条件，本门禁可能给出乐观结论。
     */
    boolean hasApplicableRewriters(Collection<Operation> operations,
                                   @org.jspecify.annotations.Nullable Class<?> entityType,
                                   Set<TableRef> tables,
                                   @org.jspecify.annotations.Nullable DbType dbType) {
        if (skipApplicableRewriteCheck(operations)) {
            return false;
        }
        for (SqlRewriter rewriter : orderedRewriters()) {
            for (Operation operation : operations) {
                if (rewriter.supports(probe(operation, entityType, dbType, tables, null, false))
                        || rewriter.supports(probe(operation, entityType, dbType, tables, PROBE_ROOT_ALIAS, false))
                        || rewriter.supports(probe(operation, entityType, dbType, tables, null, true))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SqlRewriteContext probe(Operation operation, @org.jspecify.annotations.Nullable Class<?> entityType,
                                           @org.jspecify.annotations.Nullable DbType dbType, Set<TableRef> tables,
                                           @org.jspecify.annotations.Nullable String rootAlias, boolean batch) {
        return new SqlRewriteContext(operation, entityType, dbType, tables, rootAlias, batch, null);
    }

    private boolean skipApplicableRewriteCheck(Collection<Operation> operations) {
        return rewriters.isEmpty() || operations == null || operations.isEmpty();
    }

    /**
     * 单条语句准备产物：包含完成方言注入、AST 改写与占位符校验后的最终 {@link SqlStatement}，
     * 以及准备阶段已解析出的 {@link Dialect}（供 {@link ExecutionKernel} 直接复用，避免自定义方言丢失或二次探测）。
     */
    record Prepared(SqlStatement statement, @org.jspecify.annotations.Nullable Dialect dialect) {
    }

    private List<SqlRewriter> orderedRewriters() {
        List<SqlRewriter> local = orderedRewriters;
        if (local == null) {
            synchronized (this) {
                local = orderedRewriters;
                if (local == null) {
                    local = sortRewriters(rewriters);
                    orderedRewriters = local;
                }
            }
        }
        return local;
    }

    private static List<SqlRewriter> sortRewriters(List<SqlRewriter> rewriters) {
        if (rewriters == null || rewriters.isEmpty()) {
            return List.of();
        }
        List<SqlRewriter> sorted = new ArrayList<>(rewriters);
        sorted.sort(Comparator.comparingInt(SqlRewriter::order)
                .thenComparing(r -> r.getClass().getName()));
        return List.copyOf(sorted);
    }

    /**
     * AST 级结构改写。只有框架生成的语句（{@code ast != null}）才走这条路径；
     * 原生 SQL 保持字符串语义，不解析、不改写。
     */
    private SqlStatement applyRewriters(SqlStatement statement, Map<String, Object> attributes) {
        if (rewriters.isEmpty() || statement.ast() == null) {
            return statement;
        }
        SqlNode originalNode = statement.ast();
        SqlNode node = originalNode;
        SqlRewriteContext ctx = new SqlRewriteContext(
                statement.metadata().operation(),
                statement.metadata().entityType(),
                statement.metadata().dbType(),
                statement.metadata().tables(),
                rootAlias(originalNode),
                false,
                attributes);
        for (SqlRewriter rewriter : orderedRewriters()) {
            if (rewriter.supports(ctx)) {
                node = applySingleRewriter(node, rewriter, ctx);
            }
        }
        validateRewrittenShape(originalNode, node);
        StatementMetadata metadata = resolveRewrittenMetadata(statement.metadata(), originalNode, node);
        return SqlRenderer.render(node, metadata);
    }

    private static @org.jspecify.annotations.Nullable String rootAlias(SqlNode node) {
        if (node instanceof SqlNode.Select select && select.from() instanceof SqlNode.From from) {
            return from.root().alias().isBlank() ? null : from.root().alias();
        }
        if (node instanceof SqlNode.Count count) {
            return rootAlias(count.select());
        }
        return null;
    }

    /**
     * 结构改写边界：只允许改行过滤/连接/排序列，不允许换语句类型或改 SELECT 投影。
     * SELECT 投影已经绑定了行映射器，改写列集合会让位置映射错位。
     */
    private static void validateRewrittenShape(SqlNode original, SqlNode rewritten) {
        if (original instanceof SqlNode.Select originalSelect) {
            if (!(rewritten instanceof SqlNode.Select rewrittenSelect)) {
                throw new SqlRewriteException("A SqlRewriter must not change SELECT into "
                        + rewritten.getClass().getSimpleName());
            }
            if (!originalSelect.projections().equals(rewrittenSelect.projections())) {
                throw new SqlRewriteException(
                        "A SqlRewriter must not add, remove, or reorder SELECT projections; "
                                + "use Query.select(...) for explicit projections");
            }
            return;
        }
        if (original instanceof SqlNode.Count) {
            if (!(rewritten instanceof SqlNode.Count)) {
                throw new SqlRewriteException("A SqlRewriter must keep the COUNT wrapper");
            }
            return;
        }
        if (original instanceof SqlNode.Insert) {
            if (!(rewritten instanceof SqlNode.Insert rewrittenInsert)) {
                throw new SqlRewriteException("A SqlRewriter must not change INSERT into "
                        + rewritten.getClass().getSimpleName());
            }
            requireDistinctColumns(rewrittenInsert.columns(), "INSERT");
            return;
        }
        if (original instanceof SqlNode.Upsert originalUpsert) {
            if (!(rewritten instanceof SqlNode.Upsert rewrittenUpsert)) {
                throw new SqlRewriteException("A SqlRewriter must keep the UPSERT wrapper");
            }
            requireDistinctColumns(rewrittenUpsert.insert().columns(), "UPSERT");
            return;
        }
        if (original instanceof SqlNode.Update) {
            if (!(rewritten instanceof SqlNode.Update rewrittenUpdate)) {
                throw new SqlRewriteException("A SqlRewriter must not change UPDATE into "
                        + rewritten.getClass().getSimpleName());
            }
            Set<String> columns = new LinkedHashSet<>();
            for (SqlNode.Assignment assignment : rewrittenUpdate.assignments()) {
                if (!columns.add(assignment.column())) {
                    throw new SqlRewriteException("Duplicate UPDATE assignment for column '"
                            + assignment.column() + "'");
                }
            }
            return;
        }
        if (original instanceof SqlNode.Delete && !(rewritten instanceof SqlNode.Delete)) {
            throw new SqlRewriteException("A SqlRewriter must not change DELETE into "
                    + rewritten.getClass().getSimpleName());
        }
        if (original instanceof SqlNode.Truncate && !(rewritten instanceof SqlNode.Truncate)) {
            throw new SqlRewriteException("A SqlRewriter must not change TRUNCATE into "
                    + rewritten.getClass().getSimpleName());
        }
    }

    private static void requireDistinctColumns(List<String> columns, String kind) {
        Set<String> seen = new LinkedHashSet<>();
        for (String column : columns) {
            if (!seen.add(column)) {
                throw new SqlRewriteException("Duplicate " + kind + " column '" + column + "'");
            }
        }
    }

    /**
     * 将单个 {@link SqlRewriter} 应用于 AST 节点：
     * <ul>
     *   <li>{@link SqlNode.Count}：对内层 {@link SqlNode.Select} 应用改写后重包装；</li>
     *   <li>{@link SqlNode.Upsert}：仅允许改写器显式重写外层 {@link SqlNode.Upsert}；若改写器原样返回外层节点却修改了内层
     *       {@link SqlNode.Insert}，由于各原生冲突分支均无行级 {@code WHERE} 守卫，立即抛 {@link SqlRewriteException} 显式拒绝。</li>
     * </ul>
     */
    private static SqlNode applySingleRewriter(SqlNode node, SqlRewriter rewriter, SqlRewriteContext ctx) {
        if (node instanceof SqlNode.Count count) {
            SqlNode rewritten = Objects.requireNonNull(rewriter.rewrite(count.select(), ctx),
                    "SqlRewriter must not return null");
            if (!(rewritten instanceof SqlNode.Select rewrittenSelect)) {
                throw new SqlRewriteException(
                        "A SqlRewriter must keep the inner SELECT node of a count statement");
            }
            return new SqlNode.Count(rewrittenSelect);
        }
        if (node instanceof SqlNode.Upsert upsert) {
            SqlNode direct = Objects.requireNonNull(rewriter.rewrite(upsert, ctx),
                    "SqlRewriter must not return null");
            if (direct != upsert) {
                return direct;
            }
            SqlNode innerRewritten = Objects.requireNonNull(rewriter.rewrite(upsert.insert(), ctx),
                    "SqlRewriter must not return null");
            if (innerRewritten != upsert.insert()) {
                throw new SqlRewriteException(
                        "SqlRewriter 改写了 SqlNode.Upsert 的内层 INSERT，但未显式接管外层 Upsert 冲突语义："
                                + "MERGE / ON CONFLICT / ON DUPLICATE KEY 冲突分支均无行级 WHERE 守卫，隐式改写会在主键冲突时绕过行级隔离。"
                                + "请走带谓词的查询/更新路径，或由改写器显式重写外层 SqlNode.Upsert；仅处理 SELECT/UPDATE/DELETE 的改写器请勿改写 Insert。");
            }
            return upsert;
        }
        return Objects.requireNonNull(rewriter.rewrite(node, ctx), "SqlRewriter must not return null");
    }

    /**
     * 校验改写后的表引用并刷新 {@link StatementMetadata#tables()}：
     * 禁止在 {@link SqlRewriter} 中篡改既有物理表名（分表/schema 路由归口于 {@code NamingPolicy.tableNameResolver}），
     * 同时把改写器新增的关联表引用同步回元数据。
     */
    private static StatementMetadata resolveRewrittenMetadata(
            StatementMetadata original, SqlNode originalNode, SqlNode rewrittenNode) {
        Set<TableRef> before = collectTables(originalNode);
        Set<TableRef> after = collectTables(rewrittenNode);
        if (after.isEmpty()) {
            return original;
        }
        if (!before.isEmpty() && !after.containsAll(before)) {
            throw new SqlRewriteException(
                    "SqlRewriter must not rename or remove physical tables (before=" + before
                            + ", after=" + after
                            + "); configure NamingPolicy.tableNameResolver for sharding or schema routing");
        }
        return original.withTables(after);
    }

    /** 从完整 AST 递归收集表引用（覆盖 DQL 与全部 DML/UPSERT），保证 AST 与 StatementMetadata 同源。 */
    private static Set<TableRef> collectTables(SqlNode node) {
        Set<TableRef> tables = new LinkedHashSet<>();
        collectTablesInto(node, tables);
        return tables;
    }

    private static void collectTablesInto(SqlNode node, Set<TableRef> tables) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlNode.Table table) {
            tables.add(TableRef.ofQualified(table.physicalName(), table.alias()));
        } else if (node instanceof SqlNode.From from) {
            collectTablesInto(from.root(), tables);
            for (SqlNode.Join join : from.joins()) {
                collectTablesInto(join.table(), tables);
            }
        } else if (node instanceof SqlNode.Join join) {
            collectTablesInto(join.table(), tables);
        } else if (node instanceof SqlNode.Select select) {
            collectTablesInto(select.from(), tables);
        } else if (node instanceof SqlNode.Count count) {
            collectTablesInto(count.select(), tables);
        } else if (node instanceof SqlNode.Insert insert) {
            collectTablesInto(insert.table(), tables);
        } else if (node instanceof SqlNode.Upsert upsert) {
            collectTablesInto(upsert.insert(), tables);
        } else if (node instanceof SqlNode.Update update) {
            collectTablesInto(update.table(), tables);
        } else if (node instanceof SqlNode.Delete delete) {
            collectTablesInto(delete.table(), tables);
        } else if (node instanceof SqlNode.Truncate truncate) {
            collectTablesInto(truncate.table(), tables);
        }
    }

    /**
     * 批量语句的结构改写：INSERT/UPSERT 支持追加整批同值的额外列，UPDATE/DELETE 支持整批同值的
     * 额外 WHERE 参数；逐行不同值的改写仍不在此层支持。
     */
    private BatchSqlStatement applyBatchRewriters(BatchSqlStatement batch, Map<String, Object> attributes) {
        if (rewriters.isEmpty() || batch.ast() == null) {
            return batch;
        }
        SqlNode originalNode = batch.ast();
        SqlNode node = originalNode;
        SqlRewriteContext ctx = new SqlRewriteContext(
                batch.metadata().operation(), batch.metadata().entityType(),
                batch.metadata().dbType(), batch.metadata().tables(), rootAlias(originalNode), true, attributes);
        for (SqlRewriter rewriter : orderedRewriters()) {
            if (rewriter.supports(ctx)) {
                node = applySingleRewriter(node, rewriter, ctx);
            }
        }
        validateRewrittenShape(originalNode, node);
        StatementMetadata metadata = resolveRewrittenMetadata(batch.metadata(), originalNode, node);
        if (node instanceof SqlNode.Insert || node instanceof SqlNode.Upsert) {
            SqlNode.Insert originalInsert = originalNode instanceof SqlNode.Upsert origUpsert
                    ? origUpsert.insert()
                    : (SqlNode.Insert) originalNode;
            SqlNode.Insert insert = node instanceof SqlNode.Upsert upsert
                    ? upsert.insert()
                    : (SqlNode.Insert) node;
            if (insert.rows().size() != 1) {
                throw new SqlRewriteException(
                        "Batch INSERT template AST must keep exactly one template row, got "
                                + insert.rows().size());
            }
            List<String> baseColumns = originalInsert.columns();
            if (insert.columns().size() < baseColumns.size()) {
                throw new SqlRewriteException(
                        "Batch INSERT rewrite must not remove columns");
            }
            if (!insert.columns().subList(0, baseColumns.size()).equals(baseColumns)) {
                throw new SqlRewriteException(
                        "Batch INSERT rewrite must preserve original column order and prefix (expected "
                                + baseColumns + ", got " + insert.columns().subList(0, baseColumns.size())
                                + "); extra columns may only be appended at the end");
            }
            List<SqlNode> origTemplateRow = originalInsert.rows().get(0);
            List<SqlNode> templateRow = insert.rows().get(0);
            if (templateRow.size() != insert.columns().size()) {
                throw new SqlRewriteException(
                        "Batch INSERT template row value count (" + templateRow.size()
                                + ") must match column count (" + insert.columns().size() + ")");
            }
            if (!templateRow.subList(0, origTemplateRow.size()).equals(origTemplateRow)) {
                throw new SqlRewriteException(
                        "Batch INSERT rewrite must preserve original column parameter bindings in prefix; "
                                + "extra parameters may only be appended at the end");
            }
            List<ColumnBinding> extraColumns = new ArrayList<>();
            List<Object> extraValues = new ArrayList<>();
            for (int i = baseColumns.size(); i < insert.columns().size(); i++) {
                if (!(templateRow.get(i) instanceof SqlNode.Param param)) {
                    throw new SqlRewriteException(
                            "Batch INSERT extra column '" + insert.columns().get(i)
                                    + "' must be a constant SqlNode.Param");
                }
                extraColumns.add(ColumnBinding.of(insert.columns().get(i)));
                extraValues.add(param.value());
            }
            SqlStatement rendered = SqlRenderer.render(node, metadata);
            int expectedPlaceholders = batch.columns().size() + extraColumns.size();
            int actualPlaceholders = SqlFormatUtils.countPlaceholders(rendered.sql(), metadata.dbType());
            if (actualPlaceholders != expectedPlaceholders) {
                throw new SqlRewriteException(
                        "Batch INSERT rewrite produced " + actualPlaceholders
                                + " placeholders, expected " + expectedPlaceholders);
            }
            return new BatchSqlStatement(rendered.sql(), batch.columns(), batch.rows(), metadata,
                    batch.batchSize(), batch.returnGeneratedKeys(), node, extraColumns, extraValues);
        }
        if (originalNode instanceof SqlNode.Update origUpdate && node instanceof SqlNode.Update update) {
            if (!update.assignments().equals(origUpdate.assignments())) {
                throw new SqlRewriteException(
                        "Batch UPDATE rewrite must not add, remove, or reorder SET assignments");
            }
        }
        SqlStatement originalRendered = SqlRenderer.render(originalNode, batch.metadata());
        List<SqlParameter> baseParams = originalRendered.parameters();
        SqlStatement rendered = SqlRenderer.render(node, metadata);
        int baseParamCount = batch.columns().size();
        if (rendered.parameters().size() < baseParamCount) {
            throw new SqlRewriteException(
                    "Batch UPDATE/DELETE rewrite must not remove base parameters");
        }
        if (!rendered.parameters().subList(0, baseParams.size()).equals(baseParams)) {
            throw new SqlRewriteException(
                    "Batch UPDATE/DELETE rewrite must preserve base parameter order and prefix; "
                            + "extra parameters may only be appended after existing parameters");
        }
        List<ColumnBinding> extraColumns = new ArrayList<>();
        List<Object> extraValues = new ArrayList<>();
        for (int i = baseParamCount; i < rendered.parameters().size(); i++) {
            SqlParameter parameter = rendered.parameters().get(i);
            extraColumns.add(ColumnBinding.of(parameter.name()));
            extraValues.add(parameter.value());
        }
        int expectedPlaceholders = baseParamCount + extraColumns.size();
        int actualPlaceholders = SqlFormatUtils.countPlaceholders(rendered.sql(), metadata.dbType());
        if (actualPlaceholders != expectedPlaceholders) {
            throw new SqlRewriteException(
                    "Batch UPDATE/DELETE rewrite produced " + actualPlaceholders
                            + " placeholders, expected " + expectedPlaceholders);
        }
        return new BatchSqlStatement(rendered.sql(), batch.columns(), batch.rows(), metadata,
                batch.batchSize(), batch.returnGeneratedKeys(), node, extraColumns, extraValues);
    }

    /**
     * 批量语句的唯一准备入口，与 {@link #prepare(SqlStatement, Map)} 对称：注入方言类型 → AST 改写。
     *
     * <p>批量 rewriter 只允许在列尾/参数尾追加；重排、删除或替换既有列与参数会被拒绝，
     * 避免逐行绑定静默错位。
     */
    BatchSqlStatement prepareBatch(BatchSqlStatement batch, Map<String, Object> attributes) {
        BatchSqlStatement withDbType = batch;
        if (batch.metadata().dbType() == null) {
            Dialect dialect = dialectStrategy.resolve(connections);
            if (dialect != null) {
                withDbType = injectBatchDbType(batch, dialect.dbType());
            }
        }
        BatchSqlStatement normalized = normalizeBatchPlaceholders(withDbType);
        return applyBatchRewriters(normalized, attributes);
    }

    /** 执行前的唯一准备入口：注入方言类型 → AST 改写 → 单一渲染。 */
    Prepared prepare(SqlStatement statement, Map<String, Object> attributes) {
        Objects.requireNonNull(statement, "statement must not be null");
        Dialect resolvedDialect = statement.metadata().dbType() == null
                ? dialectStrategy.resolve(connections)
                : dialectStrategy.peek();
        SqlStatement withDbType = statement.metadata().dbType() == null && resolvedDialect != null
                ? injectDbType(statement, resolvedDialect.dbType())
                : statement;
        SqlStatement normalized = normalizeNumberedParameters(withDbType);
        SqlStatement prepared = rewriters.isEmpty() ? normalized : applyRewriters(normalized, attributes);
        validateParameterContract(prepared);
        return new Prepared(prepared, resolvedDialect);
    }

    /**
     * 最终参数契约：SQL 中有效占位符数量必须与有序参数数量一致。
     * 这里覆盖原生 SQL、AST 渲染与重写器的最终产物。
     */
    private static void validateParameterContract(SqlStatement statement) {
        int placeholders = SqlFormatUtils.countPlaceholders(statement.sql(), statement.metadata().dbType());
        int parameters = statement.parameters().size();
        if (placeholders != parameters) {
            throw new IllegalArgumentException(
                    "SQL placeholder count (" + placeholders + ") does not match parameter count ("
                            + parameters + "): " + statement.sql());
        }
    }

    /**
     * 注入方言类型：AST 语句在方言确定后统一重渲一次，保证所有方言相关文本（如 LIKE 的 ESCAPE 子句）
     * 都来自同一条渲染管线，不需要按方言做字符串探测或二次补丁。
     */
    private static SqlStatement injectDbType(SqlStatement statement, DbType dbType) {
        SqlStatement updated = statement.withDbType(dbType);
        if (dbType != null && updated.ast() != null) {
            return SqlRenderer.render(updated.ast(), updated.metadata());
        }
        return updated;
    }

    private static BatchSqlStatement injectBatchDbType(BatchSqlStatement batch, DbType dbType) {
        if (dbType == null) {
            return batch;
        }
        StatementMetadata updatedMeta = batch.metadata().withDbType(dbType);
        if (batch.ast() != null) {
            SqlStatement rendered = SqlRenderer.render(batch.ast(), updatedMeta);
            return batch.withMetadata(updatedMeta).withSql(rendered.sql());
        }
        return batch.withMetadata(updatedMeta);
    }

    /**
     * 对原生 SQL（{@code ast == null}）中的编号占位符（{@code ?1}, {@code ?2}）进行规范化与参数重排：
     * <ul>
     *   <li>不含编号占位符时零分配原样返回；</li>
     *   <li>禁止在同一条 SQL 中混用匿名 {@code ?} 与编号 {@code ?N}；</li>
     *   <li>支持乱序引用（如 {@code ?2 ... ?1}）与重复引用（如 {@code ?1 ... ?1}），统一改写为标准 {@code ?} 并对齐参数列表。</li>
     * </ul>
     */
    private static SqlStatement normalizeNumberedParameters(SqlStatement statement) {
        if (statement == null || statement.ast() != null || statement.sql().indexOf('?') < 0) {
            return statement;
        }
        DbType dbType = statement.metadata().dbType();
        List<SqlFormatUtils.Placeholder> placeholders = SqlFormatUtils.scanPlaceholders(statement.sql(), dbType);
        if (placeholders.isEmpty()) {
            return statement;
        }
        boolean hasNumbered = false;
        boolean hasAnonymous = false;
        for (SqlFormatUtils.Placeholder placeholder : placeholders) {
            if (placeholder.isNumbered()) {
                hasNumbered = true;
            } else {
                hasAnonymous = true;
            }
        }
        if (!hasNumbered) {
            return statement;
        }
        if (hasAnonymous) {
            throw new IllegalArgumentException(
                    "Mixing anonymous '?' and numbered '?N' placeholders is not allowed: " + statement.sql());
        }
        List<SqlParameter> src = statement.parameters();
        List<SqlParameter> reordered = new ArrayList<>(placeholders.size());
        String sql = statement.sql();
        StringBuilder normalizedSql = new StringBuilder(sql.length());
        int copiedUntil = 0;
        for (int i = 0; i < placeholders.size(); i++) {
            SqlFormatUtils.Placeholder placeholder = placeholders.get(i);
            int targetIdx = placeholder.index();
            if (targetIdx < 1 || targetIdx > src.size()) {
                throw new IllegalArgumentException("Numbered SQL parameter ?" + targetIdx
                        + " exceeds argument count (" + src.size() + ") in SQL: " + sql);
            }
            SqlParameter param = src.get(targetIdx - 1);
            reordered.add(new SqlParameter(i + 1, param.name(), param.value(), param.source(), param.binder()));
            normalizedSql.append(sql, copiedUntil, placeholder.start()).append('?');
            copiedUntil = placeholder.end();
        }
        normalizedSql.append(sql, copiedUntil, sql.length());
        return SqlStatement.of(normalizedSql.toString(), reordered, statement.metadata());
    }

    private static BatchSqlStatement normalizeBatchPlaceholders(BatchSqlStatement batch) {
        if (batch == null || batch.ast() != null || batch.sql().indexOf('?') < 0) {
            return batch;
        }
        DbType dbType = batch.metadata().dbType();
        List<SqlFormatUtils.Placeholder> placeholders = SqlFormatUtils.scanPlaceholders(batch.sql(), dbType);
        if (placeholders.isEmpty()) {
            return batch;
        }
        boolean hasNumbered = false;
        boolean hasAnonymous = false;
        for (SqlFormatUtils.Placeholder placeholder : placeholders) {
            if (placeholder.isNumbered()) {
                hasNumbered = true;
            } else {
                hasAnonymous = true;
            }
        }
        if (!hasNumbered) {
            return batch;
        }
        if (hasAnonymous) {
            throw new IllegalArgumentException(
                    "Mixing anonymous '?' and numbered '?N' placeholders is not allowed in batch SQL: " + batch.sql());
        }
        for (int i = 0; i < placeholders.size(); i++) {
            if (placeholders.get(i).index() != i + 1) {
                throw new IllegalArgumentException(
                        "Batch SQL does not support out-of-order or repeated numbered placeholders: " + batch.sql());
            }
        }
        return batch.withSql(SqlFormatUtils.getJdbcStyleSql(batch.sql(), dbType));
    }
}
