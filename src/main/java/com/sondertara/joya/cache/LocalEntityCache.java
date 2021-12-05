package com.sondertara.joya.cache;

import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.model.TableDTO;
import com.sondertara.joya.ext.JoyaSpringContext;
import com.sondertara.joya.utils.cache.GuavaAbstractLoadingCache;
import com.sondertara.joya.utils.cache.ILocalCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Column;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.Table;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;


/**
 * 本地缓存：过期时间5min
 *
 * @author huangxiaohu
 */
public class LocalEntityCache extends GuavaAbstractLoadingCache<String, TableDTO> implements ILocalCache<String, TableDTO> {

    private static final Logger log = LoggerFactory.getLogger(LocalEntityCache.class);

    private static volatile LocalEntityCache cache = null;

    private LocalEntityCache() {
        setMaximumSize(1000);
        setExpireAfterWriteDuration(60 * 5);
    }

    public synchronized static LocalEntityCache getInstance() {
        if (null == cache) {
            synchronized (LocalEntityCache.class) {
                if (null == cache) {
                    cache = new LocalEntityCache();
                }
            }
        }
        return cache;
    }


    @Override
    protected TableDTO fetchData(String key) {
        EntityManager entityManager = JoyaSpringContext.getBean(EntityManager.class);
        Metamodel metamodel = entityManager.getEntityManagerFactory().getMetamodel();
        for (EntityType<?> entity : metamodel.getEntities()) {
            Class<?> aClass = entity.getJavaType();
            Table annotation = aClass.getAnnotation(Table.class);
            String tableName = Optional.of(annotation).map(Table::name).orElseThrow(() -> new EntityNotFoundException(StringFormatter.format("no entity found by class [{}]", key)));
            if (key.equals(aClass.getName()) || key.equalsIgnoreCase(tableName)) {
                Map<String, String> fieldNames = new LinkedHashMap<>();
                Field[] fields = aClass.getDeclaredFields();
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Column fieldAnnotation = field.getAnnotation(Column.class);
                    String columnName = Optional.ofNullable(fieldAnnotation).map(f -> StringUtils.toLowerCase(f.name())).orElse(StringUtils.toUnderlineCase(field.getName()));
                    fieldNames.put(field.getName(), columnName);
                    field.setAccessible(false);
                }
                TableDTO tableDTO = new TableDTO();
                tableDTO.setClassName(aClass.getName());
                tableDTO.setTableName(tableName);
                tableDTO.setFields(fieldNames);
                log.info("load key=[{}] from dataSource success!", key);
                if (key.equals(aClass.getName())) {
                    put(tableName, tableDTO);
                } else {
                    put(aClass.getName(), tableDTO);
                }
                return tableDTO;
            }
        }
        log.warn("no data to load by key=[{}]", key);
        return null;


    }

    @Override
    public Optional<TableDTO> get(String key) {
        TableDTO value = null;
        try {
            value = getValue(key);
        } catch (Exception e) {
            log.error("key={}获取数据异常", key, e);
        }
        return Optional.ofNullable(value);
    }

    @Override
    public void remove(String key) {
        invalidate(key);

    }


    public void removeAll() {
        invalidateAll();

    }
}