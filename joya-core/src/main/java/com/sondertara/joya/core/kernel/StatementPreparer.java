package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodeTransformer;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.kernel.support.Ordered;
import com.sondertara.joya.core.naming.NamingPolicy;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 语句编译准备管线（AST 语句与原生 SQL 共用的唯一准备入口）。
 *
 * <p>两条正交准备路径：
 * <ul>
 *   <li><b>结构化 AST 管线（{@link #prepare(AstStatement)} / {@link #prepareBatch(BatchAstStatement)}）</b>：
 *       解析方言 → 执行 {@link SqlRewriter} 链（{@code AST -> AST}）并校验形状/表引用 → 交由 {@link SqlRenderer}
 *       按 {@link NamingPolicy} 单趟编译为物理 {@link SqlStatement} / {@link BatchSqlStatement}（全程仅渲染 1 次）。</li>
 *   <li><b>原生 SQL 管线（{@link #prepare(SqlStatement)} / {@link #prepareBatch(BatchSqlStatement)}）</b>：
 *       注入方言类型 → 编号参数 {@code ?1} 规范化 → 有效占位符数量契约校验。</li>
 * </ul>
 */
final class StatementPreparer {

    private static final String PROBE_ROOT_ALIAS = "t0";

    private final List<SqlRewriter> rewriters;
    private final DialectStrategy dialectStrategy;
    private final ConnectionProvider connections;
    private final NamingPolicy namingPolicy;
    private volatile @Nullable List<SqlRewriter> orderedRewriters;

    StatementPreparer(List<SqlRewriter> rewriters,
                      DialectStrategy dialectStrategy,
                      ConnectionProvider connections,
                      NamingPolicy namingPolicy) {
        this.rewriters = rewriters == null ? List.of() : List.copyOf(rewriters);
        this.dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.namingPolicy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
    }

    List<SqlRewriter> rewriters() {
        return rewriters;
    }

    boolean hasRewriters() {
        return !rewriters.isEmpty();
    }

    /**
     * 只探测 {@code supports(ctx)}，不执行改写：用于判断优化路径（如原生批量 UPSERT）是否会被改写器影响。
     */
    boolean hasApplicableRewriters(Collection<Operation> operations,
                                   @Nullable Class<?> entityType,
                                   Set<TableRef> tables,
                                   @Nullable DbType dbType) {
        if (rewriters.isEmpty() || operations == null || operations.isEmpty()) {
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

    private static SqlRewriteContext probe(Operation operation,
                                           @Nullable Class<?> entityType,
                                           @Nullable DbType dbType,
                                           Set<TableRef> tables,
                                           @Nullable String rootAlias,
                                           boolean batch) {
        return new SqlRewriteContext(operation, entityType, dbType, tables, rootAlias, batch);
    }

    /** 单条语句准备产物（物理语句 + 已解析方言）。 */
    record Prepared(SqlStatement statement, @Nullable Dialect dialect) {
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

    // ===== 1. 结构化 AST 编译管线（单趟渲染，零重复拼接） =====

    Prepared prepare(AstStatement astStmt) {
        Objects.requireNonNull(astStmt, "astStmt must not be null");
        StatementMetadata metadata = astStmt.metadata();
        Dialect resolvedDialect = metadata.dbType() == null
                ? dialectStrategy.resolve(connections)
                : dialectStrategy.peek();
        if (metadata.dbType() == null && resolvedDialect != null) {
            metadata = metadata.withDbType(resolvedDialect.dbType());
        }
        SqlNode originalNode = astStmt.ast();
        SqlNode finalNode = originalNode;
        if (!rewriters.isEmpty()) {
            SqlRewriteContext ctx = new SqlRewriteContext(
                    metadata.operation(),
                    metadata.entityType(),
                    metadata.dbType(),
                    metadata.tables(),
                    rootAlias(originalNode),
                    false);
            for (SqlRewriter rewriter : orderedRewriters()) {
                if (rewriter.supports(ctx)) {
                    finalNode = applySingleRewriter(finalNode, rewriter, ctx);
                }
            }
            if (finalNode != originalNode) {
                validateRewrittenShape(originalNode, finalNode);
                metadata = resolveRewrittenMetadata(metadata, originalNode, finalNode);
            }
        }
        SqlStatement compiled = SqlRenderer.render(finalNode, metadata, namingPolicy);
        return new Prepared(compiled, resolvedDialect);
    }

    BatchSqlStatement prepareBatch(BatchAstStatement batchAst) {
        Objects.requireNonNull(batchAst, "batchAst must not be null");
        StatementMetadata metadata = batchAst.metadata();
        if (metadata.dbType() == null) {
            Dialect dialect = dialectStrategy.resolve(connections);
            if (dialect != null) {
                metadata = metadata.withDbType(dialect.dbType());
            }
        }
        SqlNode originalNode = batchAst.ast();
        SqlNode finalNode = originalNode;
        if (!rewriters.isEmpty()) {
            SqlRewriteContext ctx = new SqlRewriteContext(
                    metadata.operation(),
                    metadata.entityType(),
                    metadata.dbType(),
                    metadata.tables(),
                    rootAlias(originalNode),
                    true);
            for (SqlRewriter rewriter : orderedRewriters()) {
                if (rewriter.supports(ctx)) {
                    finalNode = applySingleRewriter(finalNode, rewriter, ctx);
                }
            }
            if (finalNode != originalNode) {
                validateRewrittenShape(originalNode, finalNode);
                validateBatchRewrittenAst(originalNode, finalNode, batchAst.columns());
                metadata = resolveRewrittenMetadata(metadata, originalNode, finalNode);
            }
        }
        BatchAstStatement effective = (finalNode == batchAst.ast() && metadata == batchAst.metadata())
                ? batchAst
                : new BatchAstStatement(finalNode, batchAst.columns(), batchAst.rows(), metadata,
                        batchAst.batchSize(), batchAst.returnGeneratedKeys());
        return SqlRenderer.render(effective, namingPolicy);
    }

    // ===== 2. 原生 SQL 准备管线 =====

    Prepared prepare(SqlStatement statement) {
        Objects.requireNonNull(statement, "statement must not be null");
        Dialect resolvedDialect = statement.metadata().dbType() == null
                ? dialectStrategy.resolve(connections)
                : dialectStrategy.peek();
        SqlStatement withDbType = statement.metadata().dbType() == null && resolvedDialect != null
                ? statement.withDbType(resolvedDialect.dbType())
                : statement;
        SqlStatement normalized = normalizeNumberedParameters(withDbType);
        validateParameterContract(normalized);
        return new Prepared(normalized, resolvedDialect);
    }

    BatchSqlStatement prepareBatch(BatchSqlStatement batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        BatchSqlStatement withDbType = batch;
        if (batch.metadata().dbType() == null) {
            Dialect dialect = dialectStrategy.resolve(connections);
            if (dialect != null) {
                withDbType = batch.withMetadata(batch.metadata().withDbType(dialect.dbType()));
            }
        }
        BatchSqlStatement normalized = normalizeBatchPlaceholders(withDbType);
        validateBatchParameterContract(normalized);
        return normalized;
    }

    // ===== AST 结构改写校验 =====

    private static @Nullable String rootAlias(SqlNode node) {
        if (node instanceof SqlNode.Select select && select.from() instanceof SqlNode.From from) {
            return from.root().alias().isBlank() ? null : from.root().alias();
        }
        if (node instanceof SqlNode.Count count) {
            return rootAlias(count.select());
        }
        return null;
    }

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
        if (original instanceof SqlNode.Upsert) {
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
                if (!columns.add(assignment.column().toLowerCase(Locale.ROOT))) {
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
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new SqlRewriteException("Duplicate " + kind + " column '" + column + "'");
            }
        }
    }

    private static SqlNode applySingleRewriter(SqlNode node, SqlRewriter rewriter, SqlRewriteContext ctx) {
        if (node instanceof SqlNode.Count count) {
            SqlNode rewritten = Objects.requireNonNull(rewriter.rewrite(count.select(), ctx),
                    "SqlRewriter must not return null");
            if (!(rewritten instanceof SqlNode.Select rewrittenSelect)) {
                throw new SqlRewriteException(
                        "A SqlRewriter must keep the inner SELECT node of a count statement");
            }
            return rewrittenSelect == count.select() ? count : new SqlNode.Count(rewrittenSelect);
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
        return after.equals(original.tables()) ? original : original.withTables(after);
    }

    private static Set<TableRef> collectTables(SqlNode node) {
        Set<TableRef> tables = new LinkedHashSet<>();
        collectTablesInto(node, tables);
        return tables;
    }

    private static void collectTablesInto(@Nullable SqlNode node, Set<TableRef> tables) {
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

    private static void validateBatchRewrittenAst(SqlNode originalNode, SqlNode node, List<ColumnBinding> columns) {
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
                throw new SqlRewriteException("Batch INSERT rewrite must not remove columns");
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
        } else if (originalNode instanceof SqlNode.Update origUpdate && node instanceof SqlNode.Update update) {
            if (!update.assignments().containsAll(origUpdate.assignments())) {
                throw new SqlRewriteException(
                        "Batch UPDATE rewrite must preserve all base column SET assignments");
            }
            if (origUpdate.where() != null && !preservesConjunctPredicate(update.where(), origUpdate.where())) {
                throw new SqlRewriteException(
                        "Batch UPDATE rewrite must preserve the original primary-key WHERE predicate as a top-level AND conjunct");
            }
        } else if (originalNode instanceof SqlNode.Delete origDelete && node instanceof SqlNode.Delete delete) {
            if (origDelete.where() != null && !preservesConjunctPredicate(delete.where(), origDelete.where())) {
                throw new SqlRewriteException(
                        "Batch DELETE rewrite must preserve the original WHERE predicate as a top-level AND conjunct");
            }
        }
        validateBatchRowSlots(node, columns);
    }

    private static void validateBatchRowSlots(SqlNode root, List<ColumnBinding> columns) {
        int baseCount = columns.size();
        boolean[] seen = new boolean[baseCount];
        int[] seenCount = {0};
        new SqlNodeTransformer() {
            @Override
            public SqlNode transformParam(SqlNode.Param param) {
                if (param.binding() instanceof SqlNode.Param.RowSlot slot) {
                    int idx = slot.rowIndex();
                    if (idx < 0 || idx >= baseCount) {
                        throw new SqlRewriteException(
                                "Batch rewrite referenced out-of-bounds row column index " + idx
                                        + " (column count=" + baseCount + ")");
                    }
                    if (seen[idx]) {
                        throw new SqlRewriteException(
                                "Batch rewrite must not duplicate base row parameter '"
                                        + columns.get(idx).name() + "'");
                    }
                    seen[idx] = true;
                    seenCount[0]++;
                }
                return param;
            }
        }.transform(root);
        if (seenCount[0] != baseCount) {
            for (int i = 0; i < baseCount; i++) {
                if (!seen[i]) {
                    throw new SqlRewriteException(
                            "Batch rewrite must preserve all base row parameters; missing '"
                                    + columns.get(i).name() + "' at index " + i);
                }
            }
        }
    }

    private static boolean preservesConjunctPredicate(@Nullable SqlNode rewrittenWhere, SqlNode requiredWhere) {
        if (rewrittenWhere == null) {
            return false;
        }
        if (rewrittenWhere.equals(requiredWhere)) {
            return true;
        }
        if (rewrittenWhere instanceof SqlNode.Group group) {
            return preservesConjunctPredicate(group.body(), requiredWhere);
        }
        if (rewrittenWhere instanceof SqlNode.Binary binary && binary.op() == SqlNode.BinaryOp.AND) {
            return preservesConjunctPredicate(binary.left(), requiredWhere)
                    || preservesConjunctPredicate(binary.right(), requiredWhere);
        }
        return false;
    }

    // ===== 原生 SQL 占位符规范化与契约校验 =====

    private static void validateBatchParameterContract(BatchSqlStatement batch) {
        int placeholders = SqlFormatUtils.countPlaceholders(batch.sql(), batch.metadata().dbType());
        int slotCount = batch.slots().size();
        if (placeholders != slotCount) {
            throw new IllegalArgumentException(
                    "Batch SQL placeholder count (" + placeholders + ") does not match slot count ("
                            + slotCount + "): " + batch.sql());
        }
    }

    private static void validateParameterContract(SqlStatement statement) {
        int placeholders = SqlFormatUtils.countPlaceholders(statement.sql(), statement.metadata().dbType());
        List<SqlParameter> params = statement.parameters();
        int parameters = params.size();
        if (placeholders != parameters) {
            throw new IllegalArgumentException(
                    "SQL placeholder count (" + placeholders + ") does not match parameter count ("
                            + parameters + "): " + statement.sql());
        }
    }

    private static SqlStatement normalizeNumberedParameters(SqlStatement statement) {
        if (statement == null || !SqlFormatUtils.mayContainNumberedPlaceholder(statement.sql())) {
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
        if (batch == null || !SqlFormatUtils.mayContainNumberedPlaceholder(batch.sql())) {
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
