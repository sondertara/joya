package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.DefaultChain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语句准备管线（单条与批量共用的唯一入口）：注入方言类型 → AST 结构改写（{@link SqlRewriter}）→ 文本拦截（{@link SqlInterceptor}）。
 *
 * <p>{@link ExecutionKernel} 只负责"准备好之后怎么执行"，所有改写 / 拦截语义都收敛在这里，
 * 因此查询、变更、原生 SQL 与批处理没有任何旁路。拦截器、改写器在构造时按 {@code order()} 排序一次。
 */
final class StatementPreparer {

    private final List<SqlInterceptor> interceptors;
    private final List<SqlRewriter> rewriters;
    private final DialectStrategy dialectStrategy;
    private final ConnectionProvider connections;

    StatementPreparer(List<SqlInterceptor> interceptors,
                      List<SqlRewriter> rewriters,
                      DialectStrategy dialectStrategy,
                      ConnectionProvider connections) {
        this.interceptors = sortInterceptors(interceptors);
        this.rewriters = sortRewriters(rewriters);
        this.dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
    }

    List<SqlInterceptor> interceptors() {
        return interceptors;
    }

    List<SqlRewriter> rewriters() {
        return rewriters;
    }

    boolean hasRewriters() {
        return !rewriters.isEmpty();
    }

    private static List<SqlInterceptor> sortInterceptors(List<SqlInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            return List.of();
        }
        List<SqlInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(SqlInterceptor::order)
                .thenComparing(i -> i.getClass().getName()));
        return List.copyOf(sorted);
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

    private SqlStatement rewrite(SqlStatement statement, Map<String, Object> attributes) {
        Objects.requireNonNull(statement, "statement must not be null");
        SqlStatement withDbType = statement.metadata().dbType() == null
                ? injectDbType(statement, dialectStrategy.resolve(connections).dbType())
                : statement;
        SqlStatement normalized = normalizeNumberedParameters(withDbType);
        return applyInterceptors(applyRewriters(normalized, attributes), attributes);
    }

    /** 文本拦截链（单条与批量共用）：拦截器为空时零开销直接返回。 */
    private SqlStatement applyInterceptors(SqlStatement statement, Map<String, Object> attributes) {
        if (interceptors.isEmpty()) {
            return statement;
        }
        Map<String, Object> mutableAttrs = attributes == null
                ? new HashMap<>()
                : new HashMap<>(attributes);
        SqlStatement result = new DefaultChain(interceptors).proceed(new RewriteContext(statement, mutableAttrs));
        if (attributes != null) {
            try {
                attributes.putAll(mutableAttrs);
            } catch (UnsupportedOperationException ignored) {
                // 调用方传入了只读 Map，保持向后兼容
            }
        }
        if (result == null) {
            throw new IllegalStateException("SqlInterceptor chain returned null");
        }
        boolean isBatch = attributes != null && Boolean.TRUE.equals(attributes.get(RewriteContext.ATTR_BATCH));
        if (!isBatch && (!result.sql().equals(statement.sql())
                || result.parameters().size() != statement.parameters().size())) {
            DbType dbType = result.metadata().dbType();
            int placeholders = SqlFormatUtils.countPlaceholders(result.sql(), dbType);
            if (placeholders != result.parameters().size()) {
                throw new IllegalStateException(
                        "A SqlInterceptor left placeholder count (" + placeholders
                                + ") out of sync with parameter count (" + result.parameters().size()
                                + "); use a SqlRewriter for structural parameter changes");
            }
        }
        return result;
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
                attributes);
        for (SqlRewriter rewriter : rewriters) {
            if (rewriter.supports(ctx)) {
                node = applySingleRewriter(node, rewriter, ctx);
            }
        }
        StatementMetadata metadata = resolveRewrittenMetadata(statement.metadata(), originalNode, node);
        return SqlRenderer.render(node, metadata);
    }

    /**
     * 将单个 {@link SqlRewriter} 应用于 AST 节点：
     * <ul>
     *   <li>{@link SqlNode.Count}：对内层 {@link SqlNode.Select} 应用改写后重包装；</li>
     *   <li>{@link SqlNode.Upsert}：优先直接传入 {@link SqlNode.Upsert}；若改写器原样返回（如仅针对 {@link SqlNode.Insert} 编写的租户/审计改写器），
     *       则对其内层 {@link SqlNode.Insert} 应用改写并自动同步新增更新列后重包装。</li>
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
            if (innerRewritten == upsert.insert()) {
                return upsert;
            }
            if (!(innerRewritten instanceof SqlNode.Insert rewrittenInsert)) {
                throw new SqlRewriteException(
                        "A SqlRewriter must keep the inner INSERT node of an upsert statement");
            }
            List<String> updateColumns = new ArrayList<>(upsert.updateColumns());
            for (String col : rewrittenInsert.columns()) {
                if (!col.equals(upsert.keyColumn()) && !updateColumns.contains(col)) {
                    updateColumns.add(col);
                }
            }
            return new SqlNode.Upsert(rewrittenInsert, upsert.keyColumn(), updateColumns, upsert.style());
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
                batch.metadata().dbType(), attributes);
        for (SqlRewriter rewriter : rewriters) {
            if (rewriter.supports(ctx)) {
                node = applySingleRewriter(node, rewriter, ctx);
            }
        }
        StatementMetadata metadata = resolveRewrittenMetadata(batch.metadata(), originalNode, node);
        if (node instanceof SqlNode.Insert || node instanceof SqlNode.Upsert) {
            if (!(originalNode instanceof SqlNode.Insert || originalNode instanceof SqlNode.Upsert)) {
                throw new SqlRewriteException("Batch rewrite must not change non-INSERT statement into INSERT/UPSERT");
            }
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
     * 批量语句的唯一准备入口，与 {@link #prepare(SqlStatement)} 对称：注入方言类型 → AST 改写 → 文本拦截。
     *
     * <p>文本拦截器看到的是整批共用的 SQL 模板探针（参数为空，整批只触发一次，
     * {@link RewriteContext#isBatch()} 为 {@code true}）。拦截器只可改写文本（hint / 注释）；
     * 追加参数或改变占位符个数会让逐行绑定静默错位，因此显式拒绝并提示改用 {@link SqlRewriter}。
     */
    BatchSqlStatement prepareBatch(BatchSqlStatement batch) {
        return prepareBatch(batch, null);
    }

    BatchSqlStatement prepareBatch(BatchSqlStatement batch, Map<String, Object> attributes) {
        BatchSqlStatement withDbType = batch;
        if (batch.metadata().dbType() == null) {
            Dialect dialect = (interceptors.isEmpty() && rewriters.isEmpty())
                    ? dialectStrategy.peek()
                    : dialectStrategy.resolve(connections);
            if (dialect != null) {
                withDbType = batch.withMetadata(batch.metadata().withDbType(dialect.dbType()));
            }
        }
        BatchSqlStatement normalized = normalizeBatchPlaceholders(withDbType);
        return applyBatchInterceptors(applyBatchRewriters(normalized, attributes), attributes);
    }

    private BatchSqlStatement applyBatchInterceptors(BatchSqlStatement batch, Map<String, Object> attributes) {
        if (interceptors.isEmpty()) {
            if (attributes != null) {
                try {
                    attributes.put(RewriteContext.ATTR_BATCH, Boolean.TRUE);
                    attributes.put(RewriteContext.ATTR_BATCH_ROWS, batch.rowCount());
                } catch (UnsupportedOperationException ignored) {
                }
            }
            return batch;
        }
        Map<String, Object> attrs = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        attrs.put(RewriteContext.ATTR_BATCH, Boolean.TRUE);
        attrs.put(RewriteContext.ATTR_BATCH_ROWS, batch.rowCount());
        SqlStatement probe = SqlStatement.of(batch.sql(), List.of(), batch.metadata(), batch.ast());
        SqlStatement result = applyInterceptors(probe, attrs);
        if (attributes != null) {
            try {
                attributes.putAll(attrs);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        if (result == null) {
            throw new IllegalStateException("SqlInterceptor chain returned null for a batch statement");
        }
        if (!result.parameters().isEmpty()) {
            throw new UnsupportedOperationException(
                    "A SqlInterceptor must not append parameters to a batch statement; "
                            + "use a SqlRewriter for structural changes such as tenant or audit columns");
        }
        if (result.sql().equals(batch.sql())) {
            return batch;
        }
        DbType dbType = batch.metadata().dbType();
        if (SqlFormatUtils.countPlaceholders(result.sql(), dbType)
                != SqlFormatUtils.countPlaceholders(batch.sql(), dbType)) {
            throw new UnsupportedOperationException(
                    "A SqlInterceptor must not change the placeholder count of a batch statement; "
                            + "use a SqlRewriter for structural changes");
        }
        return batch.withSql(result.sql());
    }

    /** 执行前的唯一准备入口：注入方言类型 → AST 改写 → 文本拦截。 */
    SqlStatement prepare(SqlStatement statement) {
        return prepare(statement, null);
    }

    SqlStatement prepare(SqlStatement statement, Map<String, Object> attributes) {
        if (interceptors.isEmpty() && rewriters.isEmpty()) {
            Dialect cached = dialectStrategy.peek();
            SqlStatement withDbType = (cached != null && statement.metadata().dbType() == null)
                    ? injectDbType(statement, cached.dbType())
                    : statement;
            return normalizeNumberedParameters(withDbType);
        }
        return rewrite(statement, attributes);
    }

    private static SqlStatement injectDbType(SqlStatement statement, DbType dbType) {
        SqlStatement updated = statement.withDbType(dbType);
        if (dbType != null && dbType.isLikeMysql() && updated.ast() != null && updated.sql().contains(" ESCAPE '\\'")) {
            return SqlRenderer.render(updated.ast(), updated.metadata());
        }
        return updated;
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
