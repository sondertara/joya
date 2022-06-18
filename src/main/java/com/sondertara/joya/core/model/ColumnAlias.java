package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;

/**
 * Column info for query
 *
 * @author huangxiaohu
 * @date 2021/11/15 17:17
 * @since 1.0.0
 */
@Data
public class ColumnAlias implements Serializable {

  /** table name of database eg: user */
  private String tableName;

  /** column name form database which has parsed tto lowercase eg: user_name */
  private String columnName;

  /** table alias eg: t0 */
  private String tableAlias;
  /** column alias eg: t0.user_name */
  private String columnAlias;
}
