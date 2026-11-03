package com.sondertara.joya.core.mapper;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.query.dynamic.PageQueryParams;
import com.sondertara.joya.core.query.pagination.PageQueryRequest;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.query.pagination.SortCondition;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 面向业务仓储层的统一单表 Mapper / Repository 契约。
 *
 * <p>核心契约规范：
 * <ul>
 *   <li><b>读操作</b>：单条查询统一返回 {@link Optional}，列表查询统一返回 {@link List}，分页查询统一返回 {@link PageResult}；</li>
 *   <li><b>写与删操作</b>：统一返回归一化后的影响行数 {@code int}（批处理自动消除 JDBC {@code -2}），数据库生成的主键由底层自动回写至实体对象；</li>
 *   <li>需要获取生成键列表、批处理逐行回执等底层细节时，直接通过 {@link #ops()} 使用 {@link EntityOps}。</li>
 * </ul>
 *
 * <p>同时支持两种使用方式：
 * <ul>
 *   <li><b>接口模式</b>：定义 {@code interface UserMapper extends JoyaMapper<User>}，在接口中通过 {@code default} 方法结合
 *       {@link #query()}、{@link #ops()} 或 {@link #sql(String, Object...)} 编写自定义查询，通过 {@code joya.mapper(UserMapper.class)} 获取实例；</li>
 *   <li><b>类继承模式</b>：定义 {@code class UserRepository extends JoyaRepository<User>}，直接继承全部基础方法与流式入口。</li>
 * </ul>
 *
 * @param <T> 实体类型
 * @author huangxiaohu
 */
public interface JoyaMapper<T> {

    /**
     * 获取当前绑定的底层数据操作入口（{@code JoyaClient} 或事务 {@code Tx}）。
     */
    DataOps db();

    /**
     * 获取当前关联的实体类型。
     */
    Class<T> entityClass();

    /**
     * 开启当前实体的三段式类型安全查询流（等价于 {@code db().from(entityClass())}）。
     */
    default Query<T> query() {
        return db().from(entityClass());
    }

    /**
     * 获取当前实体的领域操作门面（等价于 {@code db().of(entityClass())}）。
     */
    default EntityOps<T> ops() {
        return db().of(entityClass());
    }

    /**
     * 开启参数化原生 SQL 流（等价于 {@code db().sql(sql, params)}）。
     */
    default SqlOps sql(String sql, Object... params) {
        return db().sql(sql, params);
    }

    /*========================================write==============================================*/

    /**
     * 插入单个实体（全列写入，包含 {@code null} 列；数据库生成的主键自动回写至 {@code entity}）。
     */
    default int insert(T entity) {
        return ops().insert(entity).affectedRowsInt();
    }

    /**
     * 插入单个实体（忽略值为 {@code null} 的列；数据库生成的主键自动回写至 {@code entity}）。
     */
    default int insertIgnoreNull(T entity) {
        return ops().insertIgnoreNull(entity).affectedRowsInt();
    }

    /**
     * 批量插入全部实体（支持生成键自动回写至实体集合）。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int insertAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().insertAll((Collection<T>) entities).affectedRowsInt();
    }

    /**
     * 按主键全量更新单个实体（包含 {@code null} 列）。
     */
    default int updateById(T entity) {
        return ops().update(entity).affectedRowsInt();
    }

    /**
     * 按主键局部更新单个实体（仅更新非 {@code null} 列）。
     */
    default int patchById(T entity) {
        return ops().patch(entity).affectedRowsInt();
    }

    /**
     * 按主键批量更新全部可更新列。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int updateAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().updateAll((Collection<T>) entities).affectedRowsInt();
    }

    /**
     * 按主键批量更新指定列。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int updateAll(Collection<S> entities, IGetter<T, ?>... columns) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().updateAll((Collection<T>) entities, columns).affectedRowsInt();
    }

    /**
     * 按主键批量局部更新（仅更新每个实体的非空列）。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int patchAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().patchAll((Collection<T>) entities).affectedRowsInt();
    }

    /**
     * 保存（插入或按主键更新）单个实体，生成键自动回写至 {@code entity}。
     */
    default int save(T entity) {
        return ops().save(entity).affectedRowsInt();
    }

    /**
     * 保存（插入或按主键局部更新，忽略 {@code null} 列）单个实体，生成键自动回写至 {@code entity}。
     */
    default int saveIgnoreNull(T entity) {
        return ops().saveIgnoreNull(entity).affectedRowsInt();
    }

    /**
     * 批量保存（插入或按主键更新）实体集合。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int saveAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().saveAll((Collection<T>) entities).affectedRowsInt();
    }

    /*========================================select==============================================*/

    /**
     * 按主键查询单个实体。
     */
    default Optional<T> findById(Serializable id) {
        return ops().findById(id);
    }

    /**
     * 按单个字段等值查询单条记录。
     */
    default <F> Optional<T> findByField(IGetter<T, F> fn, F value) {
        return query().where(w -> w.eq(fn, value)).one();
    }

    /**
     * 按条件查询单条记录。
     */
    default Optional<T> findByCondition(UnaryOperator<WhereCriterion> func) {
        return query().where(func).one();
    }

    /**
     * 查询全表记录。
     */
    default List<T> findAll() {
        return ops().findAll();
    }

    /**
     * 按指定排序查询全表记录。
     */
    default List<T> findAll(SortCondition... sorts) {
        return ops().findAll(sorts);
    }

    /**
     * 按主键集合批量查询（超过 1000 条自动分片）。
     */
    default List<T> findByIds(Collection<? extends Serializable> ids) {
        return ops().findByIds(ids);
    }

    /**
     * 按单个字段等值查询列表。
     */
    default <F> List<T> findListByField(IGetter<T, F> fn, F value) {
        return query().where(w -> w.eq(fn, value)).list();
    }

    /**
     * 按条件查询列表。
     */
    default List<T> findListByCondition(UnaryOperator<WhereCriterion> func) {
        return query().where(func).list();
    }

    /**
     * 无过滤条件直接按 {@link PageRequest} 分页查询。
     */
    default PageResult<T> findPage(PageRequest pageRequest) {
        return query().page(pageRequest);
    }

    /**
     * 按条件与 {@link PageRequest} 分页查询（条件在前，{@link PageRequest} 在后）。
     */
    default PageResult<T> findPageByCondition(UnaryOperator<WhereCriterion> func, PageRequest pageRequest) {
        return query().where(func).page(pageRequest);
    }

    /**
     * 动态条件分页查询（根据 {@link PageQueryRequest} 的 {@code filters}、{@code sorts}、{@code needTotal} 自动装配）。
     */
    default PageResult<T> queryPage(PageQueryRequest request) {
        PageQueryRequest effective = request == null ? new PageQueryRequest() : request;
        return query()
                .apply(PageQueryParams.of(effective))
                .page(effective.toPageRequest());
    }

    /**
     * 判断指定主键的记录是否存在。
     */
    default boolean existsById(Serializable id) {
        return ops().existsById(id);
    }

    /**
     * 判断满足指定条件的记录是否存在。
     */
    default boolean existsByCondition(UnaryOperator<WhereCriterion> func) {
        return query().where(func).exists();
    }

    /**
     * 统计全表总行数。
     */
    default long count() {
        return ops().count();
    }

    /**
     * 统计满足指定条件的记录数。
     */
    default long countByCondition(UnaryOperator<WhereCriterion> func) {
        return query().where(func).count();
    }

    /*========================================delete==============================================*/

    /**
     * 按主键删除单条记录并返回影响行数。
     */
    default int deleteById(Serializable id) {
        return ops().deleteById(id).affectedRowsInt();
    }

    /**
     * 按主键集合批量删除并返回影响行数（超过 1000 条自动分片）。
     */
    default int deleteByIds(Collection<? extends Serializable> ids) {
        return ops().deleteByIds(ids).affectedRowsInt();
    }

    /**
     * 删除指定实体（触发 {@code EntityListener} 回调）并返回影响行数。
     */
    default int delete(T entity) {
        return ops().delete(entity).affectedRowsInt();
    }

    /**
     * 批量删除指定实体集合并返回影响行数。
     */
    @SuppressWarnings("unchecked")
    default <S extends T> int deleteAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        return ops().deleteAll((Collection<T>) entities).affectedRowsInt();
    }

    /**
     * 按条件删除记录并返回影响行数（强制要求非空 WHERE 条件）。
     */
    default int deleteByCondition(UnaryOperator<WhereCriterion> func) {
        return ops().delete().where(func).execute().affectedRowsInt();
    }

    /**
     * 删除全表所有记录（{@code DELETE FROM table}）并返回影响行数。
     */
    default int deleteAll() {
        return ops().delete().allowAll().execute().affectedRowsInt();
    }

    /**
     * 截断表（{@code TRUNCATE TABLE}）。
     */
    default void truncate() {
        ops().truncate();
    }
}
