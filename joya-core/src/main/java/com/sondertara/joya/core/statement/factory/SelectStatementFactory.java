package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SELECT 语句工厂（单表、按列名投影场景，如主键存在性探测）。
 *
 * <p>WHERE 与 {@code Query} 共用同一条 AST 路径（{@code WhereCriterion#toNode}），文本统一由 {@code SqlRenderer} 生成。
 */
public final class SelectStatementFactory {

    private SelectStatementFactory() {
    }

    /** 以默认命名策略构造单表 SELECT；{@code declaredColumns} 为空表示 {@code *}。 */
    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where, Set<String> declaredColumns) {
        return create(meta, where, declaredColumns, NamingPolicy.DEFAULT);
    }

    /**
     * 构造单表 SELECT。
     *
     * @param declaredColumns 声明的投影列（必须通过实体元数据白名单校验）；为空表示 {@code *}
     */
    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where,
                                      Set<String> declaredColumns, NamingPolicy namingPolicy) {
        RenderContext renderContext = RenderContext.forAliasedSingleTable(meta, namingPolicy);
        SqlNode.From fromNode = renderContext.toFromNode();
        SqlNode.Table rootTable = fromNode.root();

        // 1. 列选择：未声明则 *；声明列必须经过实体元数据白名单校验并携带主表别名 t0
        List<SqlNode> projections = new ArrayList<>();
        if (declaredColumns == null || declaredColumns.isEmpty()) {
            projections.add(SqlNodes.column((SqlNode.Table) null, "*"));
        } else {
            Set<String> canonicalDeclared = new LinkedHashSet<>();
            for (String col : declaredColumns) {
                canonicalDeclared.add(meta.resolveColumnName(col));
            }
            // 按元数据列顺序输出，保证稳定性
            for (String col : meta.columnNames()) {
                if (canonicalDeclared.contains(col)) {
                    projections.add(SqlNodes.column(rootTable, meta.formatColumn(col, namingPolicy)));
                }
            }
        }

        // 2. WHERE：与 Query 同一条 AST 路径（主表别名 t0）
        SqlNode whereNode = null;
        if (where != null && !where.isEmpty()) {
            whereNode = where.toNode(renderContext, ParameterSource.WHERE);
        }

        // 3. 元数据：表引用 + 实体类型
        StatementMetadata metadata = StatementMetadata.builder(Operation.SELECT)
                .tables(renderContext.extractTables())
                .entityType(meta.entityType())
                .build();

        // 4. 统一由 SqlRenderer 生成 SQL（无 Raw 表节点）
        SqlNode.Select select = new SqlNode.Select(
                false,
                projections,
                fromNode,
                whereNode,
                List.of(),
                null,
                List.of());
        return SqlRenderer.render(select, metadata);
    }
}
