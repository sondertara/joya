package com.sondertara.joya.domain;


import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.jpa.repository.BaseRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Persistable;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.PersistenceContext;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 具备增删查功能的实体父类
 * T为实体自身类型，ID为实体主键类型
 *
 * @author huangxiaohu
 */
public abstract class Model<T, ID extends Serializable> implements Serializable, Persistable<ID> {
    private static final long serialVersionUID = 6882139672754605640L;

    /**
     * 用于获取容器中bean对象的上下文，由外部用Model.setApplicationContext方法传入
     */
    private static ApplicationContext applicationContext;
    /**
     * 维护各个实体类对应的CrudRepository对象，避免重复调用applicationContext.getBean方法影响性能
     */
    private Map<String, BaseRepository<T, ID>> repositories = new HashMap<>();

    public static void setApplicationContext(ApplicationContext applicationContext) {
        Model.applicationContext = applicationContext;
    }

    @PersistenceContext
    @SuppressWarnings("unchecked")
    private BaseRepository<T, ID> getRepository() {
        // 1.获取实体对象对应的CrudRepository的bean名称，这里根据具体的命名风格来调整
        String entityClassName = getClass().getSimpleName();
        String beanName = StringUtils.lowerFirst(entityClassName) + "Repository";
        BaseRepository<T, ID> baseRepository = repositories.get(beanName);
        // 2.如果map中没有，从上下文环境获取，并放进map中
        if (Objects.isNull(baseRepository)) {
            baseRepository = (BaseRepository<T, ID>) applicationContext.getBean(beanName);
            repositories.put(beanName, baseRepository);
        }
        // 3. 返回
        return baseRepository;
    }

    /**
     * 保存当前对象
     *
     * @return 保存后的当前对象
     */
    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public T save() {
        return getRepository().save((T) this);
    }

    /**
     * 根据当前对象的id获取对象
     *
     * @return 查询到的对象
     */

    public T findById() {
        return getRepository().findById(Objects.requireNonNull(getId())).orElseThrow(() -> new NullPointerException("ID is null"));
    }

    /**
     * 删除当前对象
     */
    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public void delete() {
        getRepository().delete((T) this);
    }
}
