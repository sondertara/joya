package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 批处理语句单个 {@code ?} 占位符的绑定槽位描述。
 * <p>
 * 整批所有行共享同一份不可变槽位列表：既支持按列下标读取每行 {@code Object[]} 的值（{@link Row}），
 * 也支持在任意位置交错绑定由 {@code SqlRewriter} 注入的整批共享常量（{@link Const}，含常量 {@code null}），
 * 在保持零逐行包装分配的前提下，使单条与批量 AST 改写具备完全对称的参数契约。
 */
public sealed interface BatchSlot permits BatchSlot.Row, BatchSlot.Const {

    /** 参数逻辑名（用于观测与敏感列脱敏匹配）。 */
    String name();

    /** 参数语义来源。 */
    ParameterSource source();

    /** 参数绑定策略。 */
    ParameterBinder binder();

    /** 将本槽位的值绑定到 {@link PreparedStatement} 的指定 1 基位置。 */
    void bind(PreparedStatement ps, int jdbcIndex, Object[] row, @Nullable DbType dbType) throws SQLException;

    /** 解析本槽位在给定行上的值（供首行观测探针使用）。 */
    @Nullable Object resolveValue(Object @Nullable [] row);

    /**
     * 逐行参数槽位：绑定每行 {@code row[rowIndex]} 的值。
     *
     * @param rowIndex 行数组下标（{@code 0 <= rowIndex < columns.size()}）
     * @param binding  列级绑定描述
     * @param source   参数语义来源
     */
    record Row(int rowIndex, ColumnBinding binding, ParameterSource source) implements BatchSlot {

        public Row {
            if (rowIndex < 0) {
                throw new IllegalArgumentException("rowIndex must be >= 0: " + rowIndex);
            }
            Objects.requireNonNull(binding, "binding must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }

        @Override
        public String name() {
            return binding.name();
        }

        @Override
        public ParameterBinder binder() {
            return binding.binder();
        }

        @Override
        public void bind(PreparedStatement ps, int jdbcIndex, Object[] row, @Nullable DbType dbType)
                throws SQLException {
            binding.binder().bindValue(ps, jdbcIndex, binding.name(), row[rowIndex], dbType);
        }

        @Override
        public @Nullable Object resolveValue(Object @Nullable [] row) {
            return row != null && rowIndex < row.length ? row[rowIndex] : null;
        }
    }

    /**
     * 整批共享常量槽位：由 AST 改写器注入，整批所有行绑定同一常量值（支持 {@code null}）。
     *
     * @param name   参数逻辑名
     * @param value  常量值（可为 {@code null}）
     * @param source 参数语义来源
     * @param binder 参数绑定策略
     */
    record Const(
            String name,
            @Nullable Object value,
            ParameterSource source,
            ParameterBinder binder
    ) implements BatchSlot {

        public Const {
            name = name == null ? "" : name;
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(binder, "binder must not be null");
        }

        @Override
        public void bind(PreparedStatement ps, int jdbcIndex, Object[] row, @Nullable DbType dbType)
                throws SQLException {
            binder.bindValue(ps, jdbcIndex, name, value, dbType);
        }

        @Override
        public @Nullable Object resolveValue(Object @Nullable [] row) {
            return value;
        }
    }
}
