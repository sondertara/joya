package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SELECT 语句工厂（单表场景）。
 */
public final class SelectStatementFactory {

    private SelectStatementFactory() {
    }

    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where,
                                      Set<String> declaredColumns, String extPart) {
        return create(meta, where, declaredColumns, extPart, NamingPolicy.DEFAULT);
    }

    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where,
                                      Set<String> declaredColumns, String extPart, NamingPolicy namingPolicy) {
        // 1. 列选择：未声明则 *
        String selectPart = "*";
        if (declaredColumns != null && !declaredColumns.isEmpty()) {
            // 按元数据列顺序输出，保证稳定性
            List<String> ordered = new ArrayList<>();
            for (String col : meta.columnNames()) {
                if (declaredColumns.contains(col)) {
                    ordered.add(meta.formatColumn(col, namingPolicy));
                }
            }
            // 元数据外的声明列（例如别名列或 count(*)）原样追加
            for (String col : declaredColumns) {
                if (!meta.containsColumn(col) && !ordered.contains(col)) {
                    ordered.add(col);
                }
            }
            selectPart = String.join(", ", ordered);
        }

        // 2. WHERE 条件与参数
        SqlParameterList params = new SqlParameterList();
        StringBuilder whereSql = new StringBuilder();
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta, namingPolicy);
            where.render(renderContext, whereSql, params, ParameterSource.WHERE);
        }

        // 3. 统一由 SqlRenderer 生成 SQL
        String tableName = meta.qualifiedTableName(namingPolicy);
        String sql = SqlRenderer.renderSelect(selectPart, tableName, whereSql.toString(), extPart);

        // 4. 元数据：表引用 + 实体类型
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(tableName));
        StatementMetadata metadata = StatementMetadata.builder(Operation.SELECT)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql, params.toList(), metadata, true);
    }

    /** 便捷构造：无 extPart。 */
    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where, Set<String> declaredColumns) {
        return create(meta, where, declaredColumns, null, NamingPolicy.DEFAULT);
    }

    /** 便捷构造：按主键查询（findById 语义）。 */
    public static SqlStatement byPrimaryKey(EntityMetadataContext meta, Object id) {
        return byPrimaryKey(meta, id, NamingPolicy.DEFAULT);
    }

    /** 便捷构造：按主键查询（支持命名策略配置）。 */
    public static SqlStatement byPrimaryKey(EntityMetadataContext meta, Object id, NamingPolicy namingPolicy) {
        String pk = meta.primaryKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("entity has no primary key: " + meta.entityType().getName());
        }
        WhereCriterion where = WhereCriterion.create();
        where.eq(pk, id);
        return create(meta, where, null, null, namingPolicy);
    }
}
