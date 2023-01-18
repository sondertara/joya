package com.sondertara.joya.core.jdbc;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 数据行：封装了结果集的一行数据。
 * 数据行由若干列组成，每列都是一个值。
 * Row通过Record的getCurrentRow方法获取。
 * Row中包含了一系列getXXX方法用于获取列中的值。
 *
 * @author huangxiaohu
 * @see Record
 */
public interface Row extends Iterator<Row>, Iterable<Row>, Closeable {
    /**
     * get obj
     *
     * @param columnLabel column
     * @return column of one row
     */
    Object getObject(String columnLabel);

    /**
     * get obj
     *
     * @param columnIndex column index
     * @return column of one row
     */
    Object getObject(int columnIndex);

    /**
     * get long
     *
     * @param columnLabel name
     * @return long
     */
    long getLong(String columnLabel);

    /**
     * long
     *
     * @param columnIndex index
     * @return long
     */
    long getLong(int columnIndex);

    /**
     * get int
     *
     * @param columnLabel name
     * @return int
     */
    int getInt(String columnLabel);

    /**
     * int
     *
     * @param columnIndex index
     * @return i
     */
    int getInt(int columnIndex);

    boolean getBoolean(int columnIndex);

    boolean getBoolean(String columnLabel);

    /**
     * get int
     *
     * @param columnLabel name
     * @return int
     */
    BigDecimal getBigDecimal(String columnLabel);

    /**
     * int
     *
     * @param columnIndex index
     * @return i
     */
    BigDecimal getBigDecimal(int columnIndex);

    /**
     * get int
     *
     * @param columnLabel name
     * @return int
     */
    Date getDate(String columnLabel);

    /**
     * int
     *
     * @param columnIndex index
     * @return i
     */
    Date getDate(int columnIndex);

    /**
     * get str
     *
     * @param columnLabel name
     * @return s
     */
    String getString(String columnLabel);

    /**
     * get str
     *
     * @param columnIndex index
     * @return s
     */
    String getString(int columnIndex);

    /**
     * get double
     *
     * @param columnLabel name
     * @return d
     */
    double getDouble(String columnLabel);

    /**
     * get double
     *
     * @param columnIndex i
     * @return d
     */
    double getDouble(int columnIndex);

    /**
     * 获取列数
     *
     * @return 列数
     */
    int getColumnCount();

    /**
     * 获取列标签
     *
     * @param index 列索引（从1开始）
     * @return 列标签
     */
    String getColumnLabel(int index);


    /**
     * Returns the same object to iterate over elements of type {@code T}.
     *
     * @return an Iterator.
     */

    @Override
    default Iterator<Row> iterator() {
        return this;
    }


    default Stream<Row> toStream() {
        return StreamSupport.stream(spliterator(), false).onClose(() -> {
            try {
                close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
