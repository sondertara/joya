package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.BatchSlot;
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
import com.sondertara.joya.core.kernel.support.Ordered;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
        return new SqlRewriteContext(operation, entityType, dbType, tables, rootAlias, batch);
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
                    local = Ordered.sort(rewriters);
                    orderedRewriters = local;
                }
            }
        }
        return local;
    }

    /**
     * AST 级结构改写。只有框架生成的语句（{@code ast != null}）才走这条路径；
     * 原生 SQL 保持字符串语义，不解析、不改写。
     */
    private SqlStatement applyRewriters(SqlStatement statement) {
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
                false);
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
     * 批量语句的结构改写：保留全部原始逐行绑定列（{@link BatchSlot.Row}），同时支持改写器在任意位置
     * 追加整批同值常量参数（{@link BatchSlot.Const}，如 UPDATE SET 审计列、任意位置的 WHERE 租户条件）
     * 及无参 SQL 表达式（如 {@code version = version + 1}）；删除、重绑或重排原始行参数仍显式拒绝。
     */
    private BatchSqlStatement applyBatchRewriters(BatchSqlStatement batch) {
        if (rewriters.isEmpty() || batch.ast() == null) {
            return batch;
        }
        SqlNode originalNode = batch.ast();
        SqlNode node = originalNode;
        SqlRewriteContext ctx = new SqlRewriteContext(
                batch.metadata().operation(), batch.metadata().entityType(),
                batch.metadata().dbType(), batch.metadata().tables(), rootAlias(originalNode), true);
        for (SqlRewriter rewriter : orderedRewriters()) {
            if (rewriter.supports(ctx)) {
                node = applySingleRewriter(node, rewriter, ctx);
            }
        }
        if (node == originalNode) {
            return batch;
        }
        validateRewrittenShape(originalNode, node);
        validateBatchRewrittenAst(originalNode, node);
        StatementMetadata metadata = resolveRewrittenMetadata(batch.metadata(), originalNode, node);
        SqlStatement rendered = SqlRenderer.render(node, metadata);
        List<BatchSlot> slots = compileBatchSlots(batch.columns(), rendered.parameters());
        int actualPlaceholders = SqlFormatUtils.countPlaceholders(rendered.sql(), metadata.dbType());
        if (actualPlaceholders != slots.size()) {
            throw new SqlRewriteException(
                    "Batch rewrite produced " + actualPlaceholders
                            + " placeholders, expected " + slots.size() + ": " + rendered.sql());
        }
        return new BatchSqlStatement(rendered.sql(), batch.columns(), batch.rows(), metadata,
                batch.batchSize(), batch.returnGeneratedKeys(), node, slots);
    }

    private static void validateBatchRewrittenAst(SqlNode originalNode, SqlNode node) {
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
                                + "extra columns may only be appended at the end");
            }
            return;
        }
        if (originalNode instanceof SqlNode.Update origUpdate && node instanceof SqlNode.Update update) {
            if (!update.assignments().containsAll(origUpdate.assignments())) {
                throw new SqlRewriteException(
                        "Batch UPDATE rewrite must preserve all base column SET assignments");
            }
            if (origUpdate.where() != null && !preservesConjunctPredicate(update.where(), origUpdate.where())) {
                throw new SqlRewriteException(
                        "Batch UPDATE rewrite must preserve the original primary-key WHERE predicate as a top-level AND conjunct");
            }
            return;
        }
        if (originalNode instanceof SqlNode.Delete origDelete && node instanceof SqlNode.Delete delete) {
            if (origDelete.where() != null && !preservesConjunctPredicate(delete.where(), origDelete.where())) {
                throw new SqlRewriteException(
                        "Batch DELETE rewrite must preserve the original WHERE predicate as a top-level AND conjunct");
            }
        }
    }

    private static boolean preservesConjunctPredicate(
            @org.jspecify.annotations.Nullable SqlNode rewrittenWhere,
            SqlNode requiredWhere
    ) {
        if (rewrittenWhere == null) {
            return false;
        }
        if (rewrittenWhere.equals(requiredWhere)) {
            return true;
        }
        if (rewrittenWhere instanceof SqlNode.Group group) {
            return preservesConjunctPredicate(group.body(), requiredWhere);
        }
        if (rewrittenWhere instanceof SqlNode.Binary binary && "AND".equalsIgnoreCase(binary.op())) {
            return preservesConjunctPredicate(binary.left(), requiredWhere)
                    || preservesConjunctPredicate(binary.right(), requiredWhere);
        }
        return false;
    }

    private static List<BatchSlot> compileBatchSlots(List<ColumnBinding> columns, List<SqlParameter> parameters) {
        int baseCount = columns.size();
        boolean[] seenRow = new boolean[baseCount];
        int seenCount = 0;
        List<BatchSlot> slots = new ArrayList<>(parameters.size());
        for (SqlParameter parameter : parameters) {
            if (parameter.value() instanceof BatchSlot.RowRef ref) {
                int idx = ref.rowIndex();
                if (idx < 0 || idx >= baseCount) {
                    throw new SqlRewriteException(
                            "Batch rewrite referenced out-of-bounds row column index " + idx
                                    + " (column count=" + baseCount + ")");
                }
                if (seenRow[idx]) {
                    throw new SqlRewriteException(
                            "Batch rewrite must not duplicate base row parameter '"
                                    + columns.get(idx).name() + "'");
                }
                seenRow[idx] = true;
                seenCount++;
                slots.add(new BatchSlot.Row(idx, columns.get(idx), parameter.source()));
            } else {
                slots.add(new BatchSlot.Const(
                        parameter.name(), parameter.value(), parameter.source(), parameter.binder()));
            }
        }
        if (seenCount != baseCount) {
            for (int i = 0; i < baseCount; i++) {
                if (!seenRow[i]) {
                    throw new SqlRewriteException(
                            "Batch rewrite must preserve all base row parameters; missing '"
                                    + columns.get(i).name() + "' at index " + i);
                }
            }
        }
        return slots;
    }

    /**
     * 批量语句的唯一准备入口，与 {@link #prepare(SqlStatement)} 对称：注入方言类型 → AST 改写 → 槽位编译 → 最终契约校验。
     */
    BatchSqlStatement prepareBatch(BatchSqlStatement batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        BatchSqlStatement withDbType = batch;
        if (batch.metadata().dbType() == null) {
            Dialect dialect = dialectStrategy.resolve(connections);
            if (dialect != null) {
                withDbType = injectBatchDbType(batch, dialect.dbType());
            }
        }
        BatchSqlStatement normalized = normalizeBatchPlaceholders(withDbType);
        BatchSqlStatement prepared = applyBatchRewriters(normalized);
        validateBatchParameterContract(prepared);
        return prepared;
    }

    private static void validateBatchParameterContract(BatchSqlStatement batch) {
        int placeholders = SqlFormatUtils.countPlaceholders(batch.sql(), batch.metadata().dbType());
        int slotCount = batch.slots().size();
        if (placeholders != slotCount) {
            throw new IllegalArgumentException(
                    "Batch SQL placeholder count (" + placeholders + ") does not match slot count ("
                            + slotCount + "): " + batch.sql());
        }
    }

    /** 执行前的唯一准备入口：注入方言类型 → AST 改写 → 单一渲染。 */
    Prepared prepare(SqlStatement statement) {
        Objects.requireNonNull(statement, "statement must not be null");
        Dialect resolvedDialect = statement.metadata().dbType() == null
                ? dialectStrategy.resolve(connections)
                : dialectStrategy.peek();
        SqlStatement withDbType = statement.metadata().dbType() == null && resolvedDialect != null
                ? injectDbType(statement, resolvedDialect.dbType())
                : statement;
        SqlStatement normalized = normalizeNumberedParameters(withDbType);
        SqlStatement prepared = rewriters.isEmpty() ? normalized : applyRewriters(normalized);
        validateParameterContract(prepared);
        return new Prepared(prepared, resolvedDialect);
    }

    /**
     * 最终参数契约：SQL 中有效占位符数量必须与有序参数数量一致，且单条语句不得残留批量模板占位 {@link BatchSlot.RowRef}。
     * 这里覆盖原生 SQL、AST 渲染与重写器的最终产物。
     */
    private static void validateParameterContract(SqlStatement statement) {
        int placeholders = SqlFormatUtils.countPlaceholders(statement.sql(), statement.metadata().dbType());
        List<SqlParameter> params = statement.parameters();
        int parameters = params.size();
        if (placeholders != parameters) {
            throw new IllegalArgumentException(
                    "SQL placeholder count (" + placeholders + ") does not match parameter count ("
                            + parameters + "): " + statement.sql());
        }
        for (SqlParameter param : params) {
            if (param.value() instanceof BatchSlot.RowRef ref) {
                throw new IllegalStateException(
                        "Batch template placeholder RowRef(" + ref.rowIndex()
                                + ") must not appear in a single-row SqlStatement: " + statement.sql());
            }
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
            return new BatchSqlStatement(rendered.sql(), batch.columns(), batch.rows(), updatedMeta,
                    batch.batchSize(), batch.returnGeneratedKeys(), batch.ast(), batch.slots());
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
