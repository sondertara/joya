package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SqlNode} 不可变递归变换器（Shuttle Visitor）。
 *
 * <p>供自定义 {@code SqlRewriter} 按需覆盖感兴趣的节点方法（如 {@link #transformSelect}、
 * {@link #transformColumn}、{@link #transformBinary} 等）；默认实现递归遍历全部子节点，
 * 且当所有子节点引用均未变化时零分配原样返回原节点实例。
 */
public interface SqlNodeTransformer {

    default SqlNode transform(SqlNode node) {
        if (node instanceof SqlNode.Raw raw) {
            return transformRaw(raw);
        }
        if (node instanceof SqlNode.Table table) {
            return transformTable(table);
        }
        if (node instanceof SqlNode.Column column) {
            return transformColumn(column);
        }
        if (node instanceof SqlNode.Param param) {
            return transformParam(param);
        }
        if (node instanceof SqlNode.Func func) {
            return transformFunc(func);
        }
        if (node instanceof SqlNode.Binary binary) {
            return transformBinary(binary);
        }
        if (node instanceof SqlNode.Between between) {
            return transformBetween(between);
        }
        if (node instanceof SqlNode.In in) {
            return transformIn(in);
        }
        if (node instanceof SqlNode.Unary unary) {
            return transformUnary(unary);
        }
        if (node instanceof SqlNode.Like like) {
            return transformLike(like);
        }
        if (node instanceof SqlNode.Order order) {
            return transformOrder(order);
        }
        if (node instanceof SqlNode.Alias alias) {
            return transformAlias(alias);
        }
        if (node instanceof SqlNode.Group group) {
            return transformGroup(group);
        }
        if (node instanceof SqlNode.Assignment assignment) {
            return transformAssignment(assignment);
        }
        if (node instanceof SqlNode.From from) {
            return transformFrom(from);
        }
        if (node instanceof SqlNode.Join join) {
            return transformJoin(join);
        }
        if (node instanceof SqlNode.Select select) {
            return transformSelect(select);
        }
        if (node instanceof SqlNode.Count count) {
            return transformCount(count);
        }
        if (node instanceof SqlNode.Insert insert) {
            return transformInsert(insert);
        }
        if (node instanceof SqlNode.Upsert upsert) {
            return transformUpsert(upsert);
        }
        if (node instanceof SqlNode.Update update) {
            return transformUpdate(update);
        }
        if (node instanceof SqlNode.Delete delete) {
            return transformDelete(delete);
        }
        if (node instanceof SqlNode.Truncate truncate) {
            return transformTruncate(truncate);
        }
        throw new IllegalStateException("Unknown SqlNode: " + node.getClass().getName());
    }

    default SqlNode transformRaw(SqlNode.Raw raw) {
        return raw;
    }

    default SqlNode.Table transformTable(SqlNode.Table table) {
        return table;
    }

    default SqlNode transformColumn(SqlNode.Column column) {
        if (column.table() == null) {
            return column;
        }
        SqlNode.Table nextTable = transformTable(column.table());
        return nextTable == column.table() ? column : new SqlNode.Column(nextTable, column.name());
    }

    default SqlNode transformParam(SqlNode.Param param) {
        return param;
    }

    default SqlNode transformFunc(SqlNode.Func func) {
        if (func.args().isEmpty()) {
            return func;
        }
        List<SqlNode> nextArgs = transformList(func.args());
        return nextArgs == func.args() ? func : new SqlNode.Func(func.name(), func.distinct(), func.star(), nextArgs);
    }

    default SqlNode transformBinary(SqlNode.Binary binary) {
        SqlNode nextLeft = transform(binary.left());
        SqlNode nextRight = transform(binary.right());
        return (nextLeft == binary.left() && nextRight == binary.right())
                ? binary
                : new SqlNode.Binary(binary.op(), nextLeft, nextRight);
    }

    default SqlNode transformBetween(SqlNode.Between between) {
        SqlNode nextValue = transform(between.value());
        SqlNode nextLower = transform(between.lower());
        SqlNode nextUpper = transform(between.upper());
        return (nextValue == between.value() && nextLower == between.lower() && nextUpper == between.upper())
                ? between
                : new SqlNode.Between(nextValue, nextLower, nextUpper, between.negated());
    }

    default SqlNode transformIn(SqlNode.In in) {
        SqlNode nextValue = transform(in.value());
        List<SqlNode> nextValues = transformList(in.values());
        return (nextValue == in.value() && nextValues == in.values())
                ? in
                : new SqlNode.In(nextValue, nextValues, in.negated());
    }

    default SqlNode transformUnary(SqlNode.Unary unary) {
        SqlNode nextOperand = transform(unary.operand());
        return nextOperand == unary.operand() ? unary : new SqlNode.Unary(unary.op(), nextOperand);
    }

    default SqlNode transformLike(SqlNode.Like like) {
        SqlNode nextValue = transform(like.value());
        SqlNode nextPattern = transform(like.pattern());
        return (nextValue == like.value() && nextPattern == like.pattern())
                ? like
                : new SqlNode.Like(nextValue, nextPattern, like.negated(), like.escaped());
    }

    default SqlNode transformOrder(SqlNode.Order order) {
        SqlNode nextExpr = transform(order.expression());
        return nextExpr == order.expression() ? order : new SqlNode.Order(nextExpr, order.direction());
    }

    default SqlNode transformAlias(SqlNode.Alias alias) {
        SqlNode nextExpr = transform(alias.expression());
        return nextExpr == alias.expression() ? alias : new SqlNode.Alias(nextExpr, alias.alias());
    }

    default SqlNode transformGroup(SqlNode.Group group) {
        SqlNode nextBody = transform(group.body());
        return nextBody == group.body() ? group : new SqlNode.Group(nextBody);
    }

    default SqlNode.Assignment transformAssignment(SqlNode.Assignment assignment) {
        SqlNode nextValue = transform(assignment.value());
        return nextValue == assignment.value()
                ? assignment
                : new SqlNode.Assignment(assignment.column(), nextValue);
    }

    default SqlNode.From transformFrom(SqlNode.From from) {
        SqlNode.Table nextRoot = transformTable(from.root());
        List<SqlNode.Join> joins = from.joins();
        List<SqlNode.Join> nextJoins = joins;
        for (int i = 0; i < joins.size(); i++) {
            SqlNode.Join current = joins.get(i);
            SqlNode.Join transformed = transformJoin(current);
            if (transformed != current && nextJoins == joins) {
                nextJoins = new ArrayList<>(joins.size());
                nextJoins.addAll(joins.subList(0, i));
            }
            if (nextJoins != joins) {
                nextJoins.add(transformed);
            }
        }
        return (nextRoot == from.root() && nextJoins == joins)
                ? from
                : new SqlNode.From(nextRoot, nextJoins);
    }

    default SqlNode.Join transformJoin(SqlNode.Join join) {
        SqlNode.Table nextTable = transformTable(join.table());
        SqlNode nextOn = transform(join.on());
        return (nextTable == join.table() && nextOn == join.on())
                ? join
                : new SqlNode.Join(join.kind(), nextTable, nextOn);
    }

    default SqlNode transformSelect(SqlNode.Select select) {
        List<SqlNode> nextProjections = transformList(select.projections());
        SqlNode nextFrom = transform(select.from());
        SqlNode nextWhere = transformNullable(select.where());
        List<SqlNode> nextGroupBy = transformList(select.groupBy());
        SqlNode nextHaving = transformNullable(select.having());
        List<SqlNode> nextOrderBy = transformList(select.orderBy());
        if (nextProjections == select.projections()
                && nextFrom == select.from()
                && nextWhere == select.where()
                && nextGroupBy == select.groupBy()
                && nextHaving == select.having()
                && nextOrderBy == select.orderBy()) {
            return select;
        }
        return new SqlNode.Select(
                select.distinct(),
                nextProjections,
                nextFrom,
                nextWhere,
                nextGroupBy,
                nextHaving,
                nextOrderBy,
                select.lockMode());
    }

    default SqlNode transformCount(SqlNode.Count count) {
        SqlNode transformed = transformSelect(count.select());
        if (transformed == count.select()) {
            return count;
        }
        if (!(transformed instanceof SqlNode.Select nextSelect)) {
            throw new IllegalStateException("Count inner node must remain SqlNode.Select");
        }
        return new SqlNode.Count(nextSelect);
    }

    default SqlNode transformInsert(SqlNode.Insert insert) {
        SqlNode.Table nextTable = transformTable(insert.table());
        List<List<SqlNode>> rows = insert.rows();
        List<List<SqlNode>> nextRows = rows;
        for (int i = 0; i < rows.size(); i++) {
            List<SqlNode> row = rows.get(i);
            List<SqlNode> transformedRow = transformList(row);
            if (transformedRow != row && nextRows == rows) {
                nextRows = new ArrayList<>(rows.size());
                nextRows.addAll(rows.subList(0, i));
            }
            if (nextRows != rows) {
                nextRows.add(transformedRow);
            }
        }
        return (nextTable == insert.table() && nextRows == rows)
                ? insert
                : new SqlNode.Insert(nextTable, insert.columns(), nextRows);
    }

    default SqlNode transformUpsert(SqlNode.Upsert upsert) {
        SqlNode transformed = transformInsert(upsert.insert());
        if (transformed == upsert.insert()) {
            return upsert;
        }
        if (!(transformed instanceof SqlNode.Insert nextInsert)) {
            throw new IllegalStateException("Upsert inner node must remain SqlNode.Insert");
        }
        return new SqlNode.Upsert(nextInsert, upsert.keyColumn(), upsert.updateColumns(), upsert.style());
    }

    default SqlNode transformUpdate(SqlNode.Update update) {
        SqlNode.Table nextTable = transformTable(update.table());
        List<SqlNode.Assignment> assignments = update.assignments();
        List<SqlNode.Assignment> nextAssignments = assignments;
        for (int i = 0; i < assignments.size(); i++) {
            SqlNode.Assignment current = assignments.get(i);
            SqlNode.Assignment transformed = transformAssignment(current);
            if (transformed != current && nextAssignments == assignments) {
                nextAssignments = new ArrayList<>(assignments.size());
                nextAssignments.addAll(assignments.subList(0, i));
            }
            if (nextAssignments != assignments) {
                nextAssignments.add(transformed);
            }
        }
        SqlNode nextWhere = transformNullable(update.where());
        return (nextTable == update.table() && nextAssignments == assignments && nextWhere == update.where())
                ? update
                : new SqlNode.Update(nextTable, nextAssignments, nextWhere);
    }

    default SqlNode transformDelete(SqlNode.Delete delete) {
        SqlNode.Table nextTable = transformTable(delete.table());
        SqlNode nextWhere = transformNullable(delete.where());
        return (nextTable == delete.table() && nextWhere == delete.where())
                ? delete
                : new SqlNode.Delete(nextTable, nextWhere);
    }

    default SqlNode transformTruncate(SqlNode.Truncate truncate) {
        SqlNode.Table nextTable = transformTable(truncate.table());
        return nextTable == truncate.table() ? truncate : new SqlNode.Truncate(nextTable);
    }

    default @Nullable SqlNode transformNullable(@Nullable SqlNode node) {
        return node == null ? null : transform(node);
    }

    default List<SqlNode> transformList(List<SqlNode> nodes) {
        List<SqlNode> next = nodes;
        for (int i = 0; i < nodes.size(); i++) {
            SqlNode current = nodes.get(i);
            SqlNode transformed = transform(current);
            if (transformed != current && next == nodes) {
                next = new ArrayList<>(nodes.size());
                next.addAll(nodes.subList(0, i));
            }
            if (next != nodes) {
                next.add(transformed);
            }
        }
        return next;
    }
}
