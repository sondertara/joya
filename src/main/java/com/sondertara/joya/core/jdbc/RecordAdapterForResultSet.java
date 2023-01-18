package com.sondertara.joya.core.jdbc;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Date;
import java.util.NoSuchElementException;

/**
 * 将java.sql.ResultSet转换成自定义的Record，封装底层实现细节
 *
 * @author huangxiaohu
 */
public class RecordAdapterForResultSet implements Record, Row {
    private final ResultSet rs;
    private volatile boolean hasNext = false;
    private volatile boolean cursorReady = false;

    public RecordAdapterForResultSet(ResultSet resultSet) {
        this.rs = resultSet;
    }

    @Override
    public Object getObject(String columnLabel) {
        try {
            return rs.getObject(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public Object getObject(int columnIndex) {
        try {
            return rs.getObject(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public int getInt(String columnLabel) {
        try {
            return rs.getInt(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public int getInt(int columnIndex) {
        try {
            return rs.getInt(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public boolean getBoolean(int columnIndex) {
        try {
            return rs.getBoolean(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public boolean getBoolean(String columnLabel) {
        try {
            return rs.getBoolean(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public String getString(String columnLabel) {
        try {
            return rs.getString(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public String getString(int columnIndex) {
        try {
            return rs.getString(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public double getDouble(String columnLabel) {
        try {
            return rs.getDouble(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public double getDouble(int columnIndex) {
        try {
            return rs.getDouble(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public int getColumnCount() {
        try {
            ResultSetMetaData metaData = rs.getMetaData();
            return metaData.getColumnCount();
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public String getColumnLabel(int index) {
        try {
            ResultSetMetaData metaData = rs.getMetaData();
            return metaData.getColumnLabel(index);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasMore() {
        if (hasNext()) {
            cursorReady = false;
            return true;
        }
        return false;
    }

    @Override
    public Row getCurrentRow() {
        return this;
    }


    @Override
    public long getLong(String columnLabel) {
        try {
            return rs.getLong(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public long getLong(int columnIndex) {
        try {
            return rs.getLong(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) {
        try {
            return rs.getBigDecimal(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) {
        try {
            return rs.getBigDecimal(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public Date getDate(String columnLabel) {
        try {
            return rs.getDate(columnLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public Date getDate(int columnIndex) {
        try {
            return rs.getDate(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * The last checking closes all resources.
     */
    @Override
    public boolean hasNext() throws IllegalStateException {
        if (!cursorReady) {
            try {
                hasNext = rs.next();
                if (!hasNext) {
                    try {
                        close();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
                cursorReady = true;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return hasNext;
    }

    @Override
    public Row next() {
        if (hasNext()) {
            cursorReady = false;
            return getCurrentRow();
        }
        throw new NoSuchElementException();
    }

    /**
     * Close all resources
     */
    @Override
    public void close() throws IOException {
        if (rs != null) {
            try (ResultSet tempRs = rs) {
                cursorReady = true;
                hasNext = false;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
