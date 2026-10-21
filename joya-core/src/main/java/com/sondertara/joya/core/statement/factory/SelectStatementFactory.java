package com.sondertara.joya.core.statement.factory;

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
 * <p>
 * 规则：
 * <ul>
 *   <li>未声明列时 SELECT *；声明列时按元数据列顺序输出（稳定）。</li>
 *   <li>通过 {@link RenderContext#forSingleTable(EntityMetadataContext)} 直接渲染无表别名的 WHERE 子句与参数。</li>
 *   <li>extPart（ORDER BY / LIMIT / FOR UPDATE）原样追加。</li>
 * </ul>
 */
public final class SelectStatementFactory {

    private SelectStatementFactory() {
    }

    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where,
                                      Set<String> declaredColumns, String extPart) {
        // 1. 列选择：未声明则 *
        String selectPart = "*";
        if (declaredColumns != null && !declaredColumns.isEmpty()) {
            // 按元数据列顺序输出，保证稳定性
            List<String> ordered = new ArrayList<>();
            for (String col : meta.columnNames()) {
                if (declaredColumns.contains(col)) {
                    ordered.add(col);
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
            RenderContext renderContext = RenderContext.forSingleTable(meta);
            where.render(renderContext, whereSql, params, ParameterSource.WHERE);
        }

        // 3. 组装 SQL
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectPart).append(" FROM ").append(meta.qualifiedTableName());
        if (!whereSql.isEmpty()) {
            sql.append(" WHERE ").append(whereSql);
        }
        if (extPart != null && !extPart.isBlank()) {
            sql.append(" ").append(extPart.trim());
        }

        // 4. 元数据：表引用 + 实体类型
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(meta.qualifiedTableName()));
        StatementMetadata metadata = StatementMetadata.builder(Operation.SELECT)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql.toString(), params.toList(), metadata, true);
    }

    /** 便捷构造：无 extPart。 */
    public static SqlStatement create(EntityMetadataContext meta, WhereCriterion where, Set<String> declaredColumns) {
        return create(meta, where, declaredColumns, null);
    }

    /** 便捷构造：按主键查询（findById 语义）。 */
    public static SqlStatement byPrimaryKey(EntityMetadataContext meta, Object id) {
        String pk = meta.primaryKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("entity has no primary key: " + meta.entityType().getName());
        }
        WhereCriterion where = WhereCriterion.create();
        where.eq(pk, id);
        return create(meta, where, null, null);
    }
}
