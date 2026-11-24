package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 框架生成 SQL 的不可变强类型抽象语法树（AST 2.0）。
 *
 * <p>设计边界：
 * <ul>
 *   <li>只描述 Joya 自身构建的 DQL / DML 语句；{@code joya.sql("...")} 原生 SQL 不解析、不建树。</li>
 *   <li>节点持有<b>未加引号的规范列名</b>与强类型枚举操作符，标识符大小写及引号转义统一推迟到
 *       {@link SqlRenderer} 阶段按 {@code NamingPolicy} 执行，确保 {@code SqlRewriter} 注入的新列同样自动受命名策略约束。</li>
 *   <li>仅调用方显式传入的原生表达式片段（{@code Exprs.raw} / {@code addCondition} / {@code setExpr}）允许使用 {@link Raw} 承载。</li>
 * </ul>
 */
public sealed interface SqlNode permits
        SqlNode.Raw,
        SqlNode.Table,
        SqlNode.Column,
        SqlNode.Param,
        SqlNode.Func,
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

    /** 二元运算符结合性分类（决定同优先级子表达式是否需要括号保护）。 */
    enum Associativity {
        /** 完全结合（如 {@code AND}、{@code OR}、{@code +}）：同优先级左右子式均无需括号。 */
        ASSOCIATIVE,
        /** 左结合（如 {@code -}）：同优先级右子式必须加括号（{@code a - (b + c)}、{@code a - (b - c)}）。 */
        LEFT,
        /** 非结合（如比较运算 {@code =}、{@code <>}）：同优先级左右子式均需括号。 */
        NONE
    }

    /** 二元运算符枚举（内置运算符优先级与结合性，供渲染器按需最小化且准确地包裹括号）。 */
    enum BinaryOp {
        OR("OR", 1, Associativity.ASSOCIATIVE),
        AND("AND", 2, Associativity.ASSOCIATIVE),
        EQ("=", 3, Associativity.NONE),
        NE("<>", 3, Associativity.NONE),
        GT(">", 3, Associativity.NONE),
        GTE(">=", 3, Associativity.NONE),
        LT("<", 3, Associativity.NONE),
        LTE("<=", 3, Associativity.NONE),
        ADD("+", 4, Associativity.ASSOCIATIVE),
        SUB("-", 4, Associativity.LEFT);

        private final String symbol;
        private final int precedence;
        private final Associativity associativity;

        BinaryOp(String symbol, int precedence, Associativity associativity) {
            this.symbol = symbol;
            this.precedence = precedence;
            this.associativity = associativity;
        }

        public String symbol() {
            return symbol;
        }

        public int precedence() {
            return precedence;
        }

        public Associativity associativity() {
            return associativity;
        }

        public boolean requiresParens(BinaryOp childOp, boolean rightSide) {
            int cmp = Integer.compare(childOp.precedence, this.precedence);
            if (cmp < 0) {
                return true;
            }
            if (cmp > 0) {
                return false;
            }
            return switch (this.associativity) {
                case ASSOCIATIVE -> false;
                case LEFT -> rightSide;
                case NONE -> true;
            };
        }
    }

    /** 一元后缀断言运算符枚举。 */
    enum UnaryOp {
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        private final String sql;

        UnaryOp(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    /** 表连接类型枚举。 */
    enum JoinKind {
        JOIN("JOIN"),
        INNER_JOIN("INNER JOIN"),
        LEFT_JOIN("LEFT JOIN"),
        RIGHT_JOIN("RIGHT JOIN");

        private final String sql;

        JoinKind(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    /** 排序方向枚举。 */
    enum SortDir {
        ASC,
        DESC
    }

    /**
     * 原样 SQL 片段（仅用于调用方显式传入的原生表达式或条件片段）；
     * 片段携带的参数会在渲染时按遍历顺序统一编号。
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
     * @param logicalName  逻辑表名（供改写器做租户/审计匹配）
     * @param physicalName 已按 {@code NamingPolicy} 解析后的最终限定表名（含 schema 与引号）
     * @param alias        表别名，无别名时为空字符串
     */
    record Table(String logicalName, String physicalName, String alias) implements SqlNode {
        public Table {
            logicalName = Objects.requireNonNull(logicalName, "logicalName must not be null");
            physicalName = Objects.requireNonNull(physicalName, "physicalName must not be null");
            alias = alias == null ? "" : alias;
        }
    }

    /**
     * 列引用节点：{@code name} 为未加引号的规范列名（或通配符 {@code "*"}），渲染时按 {@code NamingPolicy} 格式化。
     */
    record Column(@Nullable Table table, String name) implements SqlNode {
        public Column {
            name = Objects.requireNonNull(name, "column name must not be null");
        }
    }

    /**
     * 参数节点：渲染为 {@code ?}，通过密封 {@link Binding} 编译期穷尽区分“已绑定常量值”与“批量模板行槽位”。
     */
    record Param(String name, Binding binding, ParameterSource source) implements SqlNode {

        /** 参数值绑定代数：保持单条与批量 {@code SqlNode.Param} 语法同构，同时隔离值来源。 */
        public sealed interface Binding permits Bound, RowSlot {
        }

        /** 单条参数或批改写注入的整批共享常量（支持 {@code null}；任意业务对象均被安全包裹）。 */
        public record Bound(@Nullable Object value) implements Binding {
        }

        /** 批量模板中的行参数槽位引用（对应每行 {@code Object[]} 的列下标）。 */
        public record RowSlot(int rowIndex) implements Binding {
            public RowSlot {
                if (rowIndex < 0) {
                    throw new IllegalArgumentException("rowIndex must be >= 0: " + rowIndex);
                }
            }
        }

        public Param {
            name = name == null ? "" : name;
            binding = Objects.requireNonNull(binding, "parameter binding must not be null");
            source = Objects.requireNonNull(source, "parameter source must not be null");
        }

        /** 构造绑定常量值的参数节点。 */
        public Param(String name, @Nullable Object value, ParameterSource source) {
            this(name, new Bound(value), source);
        }
    }

    /**
     * 结构化 SQL 函数/聚合节点（如 {@code COUNT(*)}、{@code COUNT(DISTINCT x)}、{@code MAX(x)}、{@code SUM(x)}）。
     */
    record Func(String name, boolean distinct, boolean star, List<SqlNode> args) implements SqlNode {
        public Func {
            name = Objects.requireNonNull(name, "function name must not be null");
            args = args == null ? List.of() : List.copyOf(args);
            if (star && (!args.isEmpty() || distinct)) {
                throw new IllegalArgumentException("Star function node cannot combine DISTINCT or explicit arguments");
            }
        }
    }

    /** 强类型二元表达式（逻辑连接、比较运算、算术增减）。 */
    record Binary(BinaryOp op, SqlNode left, SqlNode right) implements SqlNode {
        public Binary {
            op = Objects.requireNonNull(op, "binary operator must not be null");
            left = Objects.requireNonNull(left, "left operand must not be null");
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

    /** 一元后缀断言（{@code IS NULL} / {@code IS NOT NULL}）。 */
    record Unary(UnaryOp op, SqlNode operand) implements SqlNode {
        public Unary {
            op = Objects.requireNonNull(op, "unary operator must not be null");
            operand = Objects.requireNonNull(operand, "unary operand must not be null");
        }
    }

    /** LIKE / NOT LIKE，可选转义子句。 */
    record Like(SqlNode value, SqlNode pattern, boolean negated, boolean escaped) implements SqlNode {
        public Like {
            value = Objects.requireNonNull(value, "like value must not be null");
            pattern = Objects.requireNonNull(pattern, "like pattern must not be null");
        }
    }

    /** ORDER BY 排序项。 */
    record Order(SqlNode expression, SortDir direction) implements SqlNode {
        public Order {
            expression = Objects.requireNonNull(expression, "order expression must not be null");
            direction = Objects.requireNonNull(direction, "order direction must not be null");
        }
    }

    /** 投影列别名：{@code expression AS alias}。 */
    record Alias(SqlNode expression, String alias) implements SqlNode {
        public Alias {
            expression = Objects.requireNonNull(expression, "alias expression must not be null");
            alias = Objects.requireNonNull(alias, "alias must not be null");
        }
    }

    /** 显式括号分组节点。 */
    record Group(SqlNode body) implements SqlNode {
        public Group {
            body = Objects.requireNonNull(body, "group body must not be null");
        }
    }

    /** UPDATE SET 赋值项：{@code column} 为未加引号的规范列名。 */
    record Assignment(String column, SqlNode value) implements SqlNode {
        public Assignment {
            column = Objects.requireNonNull(column, "column must not be null");
            value = Objects.requireNonNull(value, "value must not be null");
        }
    }

    /** FROM 子句：根表 + 可选 JOIN 列表。 */
    record From(Table root, List<Join> joins) implements SqlNode {
        public From {
            root = Objects.requireNonNull(root, "from root must not be null");
            joins = joins == null ? List.of() : List.copyOf(joins);
        }
    }

    /** JOIN 关联项。 */
    record Join(JoinKind kind, Table table, SqlNode on) implements SqlNode {
        public Join {
            kind = Objects.requireNonNull(kind, "join kind must not be null");
            table = Objects.requireNonNull(table, "join table must not be null");
            on = Objects.requireNonNull(on, "join on must not be null");
        }
    }

    /** SELECT 语句节点。 */
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

        public Select withFrom(SqlNode newFrom) {
            return new Select(distinct, projections, newFrom, where, groupBy, having, orderBy, lockMode);
        }

        public Select withLockMode(LockMode newLockMode) {
            return new Select(distinct, projections, from, where, groupBy, having, orderBy, newLockMode);
        }
    }

    /** 计数子查询包装节点：{@code SELECT COUNT(*) FROM (<inner>) TEMP_T}。 */
    record Count(Select select) implements SqlNode {
        public Count {
            select = Objects.requireNonNull(select, "count select must not be null");
        }
    }

    /** INSERT 语句节点（{@code columns} 为未加引号的规范列名列表，{@code rows} 内外层均为不可变列表）。 */
    record Insert(Table table, List<String> columns, List<List<SqlNode>> rows) implements SqlNode {
        public Insert {
            table = Objects.requireNonNull(table, "table must not be null");
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = freezeRows(rows);
        }

        @SuppressWarnings("unchecked")
        private static List<List<SqlNode>> freezeRows(@Nullable List<List<SqlNode>> rows) {
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            if (rows.size() == 1) {
                return List.of(List.copyOf(Objects.requireNonNull(rows.get(0), "insert row must not be null")));
            }
            List<SqlNode>[] copy = new List[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                copy[i] = List.copyOf(Objects.requireNonNull(rows.get(i), "insert row must not be null"));
            }
            return List.of(copy);
        }
    }

    /** 原生 UPSERT 语句节点（{@code keyColumn} 与 {@code updateColumns} 为未加引号的规范列名）。 */
    record Upsert(Insert insert, String keyColumn, List<String> updateColumns, UpsertStyle style) implements SqlNode {
        public Upsert {
            insert = Objects.requireNonNull(insert, "upsert insert must not be null");
            keyColumn = Objects.requireNonNull(keyColumn, "upsert key column must not be null");
            updateColumns = updateColumns == null ? List.of() : List.copyOf(updateColumns);
            style = Objects.requireNonNull(style, "upsert style must not be null");
        }
    }

    /** UPDATE 语句节点。 */
    record Update(Table table, List<Assignment> assignments, @Nullable SqlNode where) implements SqlNode {
        public Update {
            table = Objects.requireNonNull(table, "table must not be null");
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
        }

        public Update withWhere(@Nullable SqlNode newWhere) {
            return new Update(table, assignments, newWhere);
        }
    }

    /** DELETE 语句节点。 */
    record Delete(Table table, @Nullable SqlNode where) implements SqlNode {
        public Delete {
            table = Objects.requireNonNull(table, "table must not be null");
        }

        public Delete withWhere(@Nullable SqlNode newWhere) {
            return new Delete(table, newWhere);
        }
    }

    /** TRUNCATE 语句节点。 */
    record Truncate(Table table) implements SqlNode {
        public Truncate {
            table = Objects.requireNonNull(table, "table must not be null");
        }
    }
}
