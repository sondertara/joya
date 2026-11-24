package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 单列标量结果集行映射器（支持 {@link #forResultSet(ResultSet)} 列类名预解析与预绑定 {@link SqlDataUtils.ColumnReader}）。
 *
 * @author huangxiaohu
 */
public class SingleColumnRowMapper<T> implements RowMapper<T> {

    private final @Nullable Class<T> clazz;
    private final @Nullable Class<?> boxedType;
    private final SqlDataUtils.ColumnReader reader;

    public SingleColumnRowMapper(@Nullable Class<T> clazz) {
        this.clazz = clazz;
        this.boxedType = clazz == null ? null : TypeUtils.getWrapClass(clazz);
        this.reader = SqlDataUtils.readerFor(clazz);
    }

    @Override
    public RowMapper<T> forResultSet(ResultSet rs) throws SQLException {
        String colClassName = SqlDataUtils.safeGetColumnClassName(rs.getMetaData(), 1);
        return row -> readColumn(row, colClassName);
    }

    @Override
    public T map(ResultSet row) throws SQLException {
        return readColumn(row, null);
    }

    @SuppressWarnings("unchecked")
    private T readColumn(ResultSet row, @Nullable String colClassName) throws SQLException {
        Object val = reader.read(row, 1, colClassName);
        if (val == null) {
            return null;
        }
        if (clazz != null && boxedType != null && !boxedType.isInstance(val)) {
            try {
                return ConvertUtils.convert(val, clazz);
            } catch (RuntimeException e) {
                throw new DbException("Failed to convert column 1 as " + clazz.getName()
                        + ": " + e.getMessage(), e);
            }
        }
        return (T) val;
    }
}
