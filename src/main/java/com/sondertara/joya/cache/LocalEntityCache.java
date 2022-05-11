package com.sondertara.joya.cache;

import com.sondertara.joya.core.model.TableDTO;
import com.sondertara.joya.utils.cache.GuavaAbstractLoadingCache;
import com.sondertara.joya.utils.cache.ILocalCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;


/**
 * local cache for five min
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

    private AnstractTableResult tableResult;

    public synchronized static LocalEntityCache getInstance() {
        if (null == cache) {
            synchronized (LocalEntityCache.class) {
                if (null == cache) {
                    cache = new LocalEntityCache();
                    cache.setTableResult(new EntityManagerTableResultAdapter());
                }
            }
        }
        return cache;
    }

    public void setTableResult(AnstractTableResult tableResult) {
        this.tableResult = tableResult;
    }

    @Override
    protected TableDTO fetchData(String key) {

        List<TableDTO> list = tableResult.load();
        for (TableDTO tableDTO : list) {
            if (tableDTO.getTableName().equalsIgnoreCase(key)) {
                put(tableDTO.getClassName(), tableDTO);

            } else if (tableDTO.getClassName().equalsIgnoreCase(key)) {
                put(tableDTO.getTableName(), tableDTO);
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