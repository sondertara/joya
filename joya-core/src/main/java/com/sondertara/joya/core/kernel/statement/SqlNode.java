package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 框架生成 SQL 的内部结构树（AST-lite）。
 *
 * <p>设计边界：
 * <ul>
 *   <li>只描述 Joya 自己生成的 SQL；{@code joya.sql("...")} 原生 SQL 不解析、不建树。</li>
 *   <li>节点是不可变数据载体，不含渲染逻辑；渲染统一由 {@link SqlRenderer} 完成。</li>
 *   <li>SELECT 的投影列允许使用 {@link Raw} 节点包住既有片段，避免一次性重写整个 DSL。</li>
 * </ul>
 *
 * <p>引入它的目的不是新增公共查询 API，而是让扩展点可以在“结构层”改写 SQL：
 * 加租户条件、软删过滤、追加列（物理表名解析统一归口于 {@code NamingPolicy.tableNameResolver}），
 * 而不是对已渲染字符串做正则替换。
 */
public sealed interface SqlNode permits
        SqlNode.Raw,
        SqlNode.Table,
        SqlNode.Column,
        SqlNode.Param,
        SqlNode.Binary,
        SqlNode.Between,
        SqlNode.In,
        SqlNode.Unary,
        SqlNode.Like,
        SqlNode.Order,
        SqlNode.Alias,
        SqlNode.Group,
        SqlNode.Assignment,
        SqlNode.Select,
        SqlNode.From,
        SqlNode.Join,
        SqlNode.Count,
        SqlNode.Insert,
        SqlNode.Upsert,
        SqlNode.Update,
        SqlNode.Delete,
        SqlNode.Truncate {

    /**
     * 原样 SQL 片段。用于 SELECT 表达式投影或调用方显式传入的原生条件片段；
     * 片段携带的参数会在渲染时按顺序重新编号。
     */
    record Raw(String sql, List<SqlParameter> parameters) implements SqlNode {
        public Raw {
            sql = Objects.requireNonNull(sql, "raw sql must not be null");
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /**
     * 表节点。
     *
     * @param logicalName  逻辑表名（改写器只读，用于租户/审计判断）
     * @param physicalName 已按 {@code NamingPolicy} 解析后的最终表名（含 schema、引号）
     * @param alias        表别名，无别名为空字符串
     */
    record Table(String logicalName, String physicalName, String alias) implements SqlNode {
        public Table {
            logicalName = Objects.requireNonNull(logicalName, "logicalName must not be null");
            physicalName = Objects.requireNonNull(physicalName, "physicalName must not be null");
            alias = alias == null ? "" : alias;
        }
    }

    /** 列节点：{@code alias.name} 或 {@code name}。 */
    record Column(@Nullable Table table, String name) implements SqlNode {
        public Column {
            name = Objects.requireNonNull(name, "column name must not be null");
        }
    }

    /** 参数节点：渲染为 {@code ?}，并按遍历顺序写入参数列表。 */
    record Param(String name, @Nullable Object value, ParameterSource source) implements SqlNode {
        public Param {
            name = name == null ? "" : name;
            source = Objects.requireNonNull(source, "parameter source must not be null");
        }
    }

    /** 二元表达式：AND / OR / = / <> / &gt; / &lt; / + / - 等。 */
    record Binary(String op, @Nullable SqlNode left, SqlNode right) implements SqlNode {
        public Binary {
            op = Objects.requireNonNull(op, "operator must not be null");
            right = Objects.requireNonNull(right, "right operand must not be null");
        }
    }

    /** BETWEEN / NOT BETWEEN。 */
    record Between(SqlNode value, SqlNode lower, SqlNode upper, boolean negated) implements SqlNode {
        public Between {
            value = Objects.requireNonNull(value, "between value must not be null");
            lower = Objects.requireNonNull(lower, "between lower must not be null");
            upper = Objects.requireNonNull(upper, "between upper must not be null");
        }
    }

    /** IN / NOT IN。 */
    record In(SqlNode value, List<SqlNode> values, boolean negated) implements SqlNode {
        public In {
            value = Objects.requireNonNull(value, "in value must not be null");
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** 一元后缀操作，如 {@code IS NULL} / {@code IS NOT NULL}。 */
    record Unary(String op, SqlNode operand) implements SqlNode {
        public Unary {
            op = Objects.requireNonNull(op, "unary operator must not be null");
            operand = Objects.requireNonNull(operand, "unary operand must not be null");
        }
    }

    /** LIKE / NOT LIKE，可选 {@code ESCAPE '\'}。 */
    record Like(SqlNode value, SqlNode pattern, boolean negated, boolean escaped) implements SqlNode {
        public Like {
            value = Objects.requireNonNull(value, "like value must not be null");
            pattern = Objects.requireNonNull(pattern, "like pattern must not be null");
        }
    }

    /** ORDER BY 元素：表达式 + ASC/DESC。 */
    record Order(SqlNode expression, String direction) implements SqlNode {
        public Order {
            expression = Objects.requireNonNull(expression, "order expression must not be null");
            direction = Objects.requireNonNull(direction, "order direction must not be null");
        }
    }

    /** 投影别名：{@code expression AS alias}。 */
    record Alias(SqlNode expression, String alias) implements SqlNode {
        public Alias {
            expression = Objects.requireNonNull(expression, "alias expression must not be null");
            alias = Objects.requireNonNull(alias, "alias must not be null");
        }
    }

    /** 显式括号节点。 */
    record Group(SqlNode body) implements SqlNode {
        public Group {
            body = Objects.requireNonNull(body, "group body must not be null");
        }
    }

    /** UPDATE SET 赋值：{@code column = value}。 */
    record Assignment(String column, SqlNode value) implements SqlNode {
        public Assignment {
            column = Objects.requireNonNull(column, "column must not be null");
            value = Objects.requireNonNull(value, "value must not be null");
        }
    }

    /** FROM 节点：根表 + 可选 JOIN 列表。 */
    record From(Table root, List<Join> joins) implements SqlNode {
        public From {
            root = Objects.requireNonNull(root, "from root must not be null");
            joins = joins == null ? List.of() : List.copyOf(joins);
        }
    }

    /** JOIN 节点：{@code type} 为 INNER JOIN / LEFT JOIN 等 SQL 片段。 */
    record Join(String type, Table table, SqlNode on) implements SqlNode {
        public Join {
            type = Objects.requireNonNull(type, "join type must not be null");
            table = Objects.requireNonNull(table, "join table must not be null");
            on = Objects.requireNonNull(on, "join on must not be null");
        }
    }

    /** SELECT 节点；表达式投影允许 Raw。 */
    record Select(boolean distinct,
                  List<SqlNode> projections,
                  SqlNode from,
                  @Nullable SqlNode where,
                  List<SqlNode> groupBy,
                  @Nullable SqlNode having,
                  List<SqlNode> orderBy,
                  LockMode lockMode) implements SqlNode {
        public Select {
            projections = projections == null ? List.of() : List.copyOf(projections);
            from = Objects.requireNonNull(from, "from must not be null");
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
            lockMode = lockMode == null ? LockMode.NONE : lockMode;
        }

        public Select(boolean distinct,
                      List<SqlNode> projections,
                      SqlNode from,
                      @Nullable SqlNode where,
                      List<SqlNode> groupBy,
                      @Nullable SqlNode having,
                      List<SqlNode> orderBy) {
            this(distinct, projections, from, where, groupBy, having, orderBy, LockMode.NONE);
        }

        public Select withWhere(@Nullable SqlNode newWhere) {
            return new Select(distinct, projections, from, newWhere, groupBy, having, orderBy, lockMode);
        }

        public Select withLockMode(LockMode newLockMode) {
            return new Select(distinct, projections, from, where, groupBy, having, orderBy, newLockMode);
        }
    }

    /** 计数包装节点：{@code SELECT COUNT(*) FROM (<inner>) TEMP_T}。 */
    record Count(Select select) implements SqlNode {
        public Count {
            select = Objects.requireNonNull(select, "count select must not be null");
        }
    }

    /** INSERT 节点；单行与多行共用。 */
    record Insert(Table table, List<String> columns, List<List<SqlNode>> rows) implements SqlNode {
        public Insert {
            table = Objects.requireNonNull(table, "table must not be null");
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** 原生 upsert 节点：INSERT + 方言 upsert 策略。 */
    record Upsert(Insert insert, String keyColumn, List<String> updateColumns, UpsertStyle style) implements SqlNode {
        public Upsert {
            insert = Objects.requireNonNull(insert, "upsert insert must not be null");
            keyColumn = Objects.requireNonNull(keyColumn, "upsert key column must not be null");
            updateColumns = updateColumns == null ? List.of() : List.copyOf(updateColumns);
            style = Objects.requireNonNull(style, "upsert style must not be null");
        }
    }

    /** UPDATE 节点。 */
    record Update(Table table, List<Assignment> assignments, @Nullable SqlNode where) implements SqlNode {
        public Update {
            table = Objects.requireNonNull(table, "table must not be null");
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
        }

        public Update withWhere(@Nullable SqlNode newWhere) {
            return new Update(table, assignments, newWhere);
        }
    }

    /** DELETE 节点。 */
    record Delete(Table table, @Nullable SqlNode where) implements SqlNode {
        public Delete {
            table = Objects.requireNonNull(table, "table must not be null");
        }

        public Delete withWhere(@Nullable SqlNode newWhere) {
            return new Delete(table, newWhere);
        }
    }

    /** TRUNCATE 节点。 */
    record Truncate(Table table) implements SqlNode {
        public Truncate {
            table = Objects.requireNonNull(table, "table must not be null");
        }
    }
}
