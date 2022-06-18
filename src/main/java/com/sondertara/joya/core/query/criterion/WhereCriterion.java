package com.sondertara.joya.core.query.criterion;

import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.TaraFunction;
import com.sondertara.common.util.CollectionUtils;
import com.sondertara.common.util.RegexUtils;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.cache.AliasThreadLocalCache;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.utils.SqlUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

/**
 * where or having condition
 *
 * @author huangxiaohu
 */
public class WhereCriterion {

  /** where condition segments */
  private final StringJoiner segments;
  /** params */
  private final List<Object> params;
  /** 占位符计数器 ?1 ?2 ?3 */
  private int counts = 1;
  /** the link type for where segments */
  private final Operator currentOpt;

  /** 默认用 and 连接 查询条件 */
  public WhereCriterion() {
    this(Operator.AND);
  }

  /**
   * 指定查询条件之间是用 and 还是 or 连接
   *
   * @param operator Operator.AND/Operator.OR
   */
  public WhereCriterion(Operator operator) {
    this.currentOpt = operator;
    this.segments = new StringJoiner(" " + operator.name() + " ");
    this.params = new ArrayList<>();
  }

  public static WhereCriterion get() {
    return new WhereCriterion();
  }

  /** where 条件 begin */
  public WhereCriterion eq(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} = ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /** where 条件 begin */
  public <T> WhereCriterion eq(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} = ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /** where 条件 begin */
  public <T, R> WhereCriterion eq(TaraFunction<T, ?> left, TaraFunction<R, ?> right) {
    if (null == left || null == right) {
      throw new TaraException("The params is required");
    }
    String tableColumnL = AliasThreadLocalCache.getColumn(left).getColumnAlias();
    String tableColumnR = AliasThreadLocalCache.getColumn(right).getColumnAlias();
    segments.add(StringUtils.format("{} = {}", tableColumnL, tableColumnR));
    return this;
  }

  /** 子查询 */
  public WhereCriterion subQuery(UnaryOperator<WhereCriterion> func) throws RuntimeException {

    Operator operator = Operator.OR.equals(this.currentOpt) ? Operator.AND : Operator.OR;
    WhereCriterion apply = func.apply(new WhereCriterion(operator));

    StringJoiner joiner = apply.getSegments();

    if (Objects.isNull(joiner)) {
      throw new RuntimeException("sub query is null!");
    }
    List<Object> params = apply.getParams();
    if (apply.getCounts() < JoyaConst.TWO_QUERY_COUNT) {
      throw new RuntimeException("sub query must a least two part!");
    }
    String subSql = joiner.toString();
    List<String> all = RegexUtils.findAll("(\\s\\?)[0-9]+", subSql, 0);

    String[] split = subSql.split(" " + operator.name() + " ");
    if (CollectionUtils.isNotEmpty(all)) {
      for (int i = 0; i < all.size(); i++) {
        this.counts += i;
        split[i] = split[i].replace(all.get(i), StringFormatter.format(" ?{}", this.counts));
      }
    }
    subSql = String.join(" " + operator.name() + " ", split);
    this.params.addAll(params);
    this.segments.add(StringFormatter.format("( {} )", subSql));

    return this;
  }

  /**
   * 不等于
   *
   * @param columnName column column
   * @param value value value
   * @return where criterion
   */
  public WhereCriterion ne(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} != ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 小于
   *
   * @param fn column 列名
   * @param value value 值
   * @return where criterion
   */
  public <T> WhereCriterion lt(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} < ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 小于
   *
   * @param columnName column 列名
   * @param value value 值
   * @return where criterion
   */
  public WhereCriterion lt(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} < ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 小于等于
   *
   * @param fn column 列名
   * @param value value value
   * @return where criterion
   */
  public <T> WhereCriterion lte(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} <= ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 小于等于
   *
   * @param columnName column column
   * @param value value value
   * @return where criterion
   */
  public WhereCriterion lte(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} <= ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 大于
   *
   * @param fn column 列名
   * @param value value value
   * @return where criterion
   */
  public <T> WhereCriterion gt(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} > ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 大于
   *
   * @param columnName column column
   * @param value value value
   * @return where criterion
   */
  public WhereCriterion gt(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} > ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 大于等于
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion gte(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} >= ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * 大于等于
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion gte(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} >= ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * is null
   *
   * @param fn column
   * @return where criterion
   */
  public <T> WhereCriterion isNull(TaraFunction<T, ?> fn) {
    String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
    segments.add(tableColumn + " IS NULL");
    return this;
  }

  /**
   * is null
   *
   * @param columnName column
   * @return where criterion
   */
  public WhereCriterion isNull(String columnName) {
    segments.add(AliasThreadLocalCache.getColumnName(columnName) + " IS NULL");
    return this;
  }

  /**
   * is not null
   *
   * @param fn column
   * @return where criterion
   */
  public <T> WhereCriterion isNotNull(TaraFunction<T, ?> fn) {
    String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();

    segments.add(tableColumn + " IS NOT NULL");
    return this;
  }

  /**
   * is not null
   *
   * @param columnName column
   * @return where criterion
   */
  public WhereCriterion isNotNull(String columnName) {
    segments.add(AliasThreadLocalCache.getColumnName(columnName) + " IS NOT NULL");
    return this;
  }

  /**
   * right like
   *
   * @param fn column column name
   * @param value value filed
   * @return where criterion
   */
  public <T> WhereCriterion startsWith(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
      params.add(value + "%");
    }
    return this;
  }

  /**
   * right like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion startsWith(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      AliasThreadLocalCache.getColumnName(columnName);
      segments.add(
          StringUtils.format(
              "{} LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value + "%");
    }
    return this;
  }

  /**
   * like
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion contains(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
      params.add("%" + value + "%");
    }
    return this;
  }

  /**
   * like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion contains(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add("%" + value + "%");
    }
    return this;
  }

  /**
   * left like
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion endsWith(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
      params.add("%" + value);
    }
    return this;
  }

  /**
   * left like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion endsWith(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add("%" + value);
    }
    return this;
  }

  /**
   * not right like
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion notStartsWith(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
      params.add(value + "%");
    }
    return this;
  }

  /**
   * not right like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion notStartsWith(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} NOT LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value + "%");
    }
    return this;
  }

  /**
   * not like
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion notContains(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
      params.add("%" + value + "%");
    }
    return this;
  }

  /**
   * not like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion notContains(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} NOT LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add("%" + value + "%");
    }
    return this;
  }

  /**
   * not left like
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion notEndsWith(String columnName, Object value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} NOT LIKE ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add("%" + value);
    }
    return this;
  }

  /**
   * not left like
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion notEndsWith(TaraFunction<T, ?> fn, Object value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
      params.add("%" + value);
    }
    return this;
  }

  /**
   * in
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion in(String columnName, Collection<Object> value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} IN ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * in
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion in(TaraFunction<T, ?> fn, Collection<Object> value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} IN ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * not in
   *
   * @param columnName column
   * @param value value
   * @return where criterion
   */
  public WhereCriterion notIn(String columnName, Collection<Object> value) {
    if (Objects.nonNull(value)) {
      segments.add(
          StringUtils.format(
              "{} NOT IN ?{}", AliasThreadLocalCache.getColumnName(columnName), counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * not in
   *
   * @param fn column
   * @param value value
   * @return where criterion
   */
  public <T> WhereCriterion notIn(TaraFunction<T, ?> fn, Collection<Object> value) {
    if (Objects.nonNull(value)) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} NOT IN ?{}", tableColumn, counts++));
      params.add(value);
    }
    return this;
  }

  /**
   * between
   *
   * @param columnName column
   * @param values values
   * @return where criterion
   */
  public WhereCriterion between(String columnName, List<Object> values) {
    if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
      segments.add(
          StringUtils.format(
              "{} BETWEEN ?{} AND ?{}",
              AliasThreadLocalCache.getColumnName(columnName),
              counts++,
              counts++));
      params.addAll(values);
    }
    return this;
  }

  /**
   * between
   *
   * @param fn column
   * @param values values
   * @return where criterion
   */
  public <T> WhereCriterion between(TaraFunction<T, ?> fn, List<Object> values) {
    if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(StringUtils.format("{} BETWEEN ?{} AND ?{}", tableColumn, counts++, counts++));
      params.addAll(values);
    }
    return this;
  }

  /**
   * not between
   *
   * @param columnName column
   * @param values values
   * @return where criterion
   */
  public WhereCriterion notBetween(String columnName, List<Object> values) {
    if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
      segments.add(
          StringUtils.format(
              "{} NOT BETWEEN ?{} AND ?{}",
              AliasThreadLocalCache.getColumnName(columnName),
              counts++,
              counts++));
      params.addAll(values);
    }
    return this;
  }

  /**
   * not between
   *
   * @param fn column
   * @param values values
   * @return where criterion
   */
  public <T> WhereCriterion notBetween(TaraFunction<T, ?> fn, List<Object> values) {
    if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
      String tableColumn = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
      segments.add(
          StringUtils.format("{} NOT BETWEEN ?{} AND ?{}", tableColumn, counts++, counts++));
      params.addAll(values);
    }
    return this;
  }

  /** 追加到 where条件中自定义sql字符串片段 */
  public void addCondition(String part) {
    this.segments.add(SqlUtils.underlineColumn(part));
  }

  public StringJoiner getSegments() {
    return this.segments;
  }

  public int getCounts() {
    return counts - 1;
  }

  public List<Object> getParams() {
    return params;
  }

  public enum Operator {
    /** */
    EQ,
    NE,
    LT,
    LTE,
    GT,
    GTE,
    ISNULL,
    IS_NOT_NULL,
    ISEMPTY,
    IS_NOT_EMPTY,
    LIKE,
    NOT_LIKE,
    IN,
    NOTIN,
    BETWEEN,
    NOT_BETWEEN,
    AND,
    OR
  }
}
