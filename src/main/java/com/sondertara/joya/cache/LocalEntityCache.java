package com.sondertara.joya.cache;

import com.sondertara.common.cache.GuavaAbstractLoadingCache;
import com.sondertara.joya.core.data.TableResultLoader;
import com.sondertara.joya.core.data.EntityManagerTableResultLoaderAdapter;
import com.sondertara.joya.core.model.TableStructDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;


/**
 * local cache for five min
 *
 * @author huangxiaohu
 */
public class LocalEntityCache extends GuavaAbstractLoadingCache<String, TableStructDef> {

    private static final Logger log = LoggerFactory.getLogger(LocalEntityCache.class);

    private static volatile LocalEntityCache cache = null;

    private LocalEntityCache() {
        setMaximumSize(1000);
        setExpireAfterWriteDuration(60 * 5);
    }

    private TableResultLoader tableResult;


    public static void setTableResultAdapter(TableResultLoader tableResult) {
        getInstance().setTableResult(tableResult);
    }

    public synchronized static LocalEntityCache getInstance() {
        if (null == cache) {
            synchronized (LocalEntityCache.class) {
                if (null == cache) {
                    cache = new LocalEntityCache();
                    cache.setTableResult(new EntityManagerTableResultLoaderAdapter());
                }
            }
        }
        return cache;
    }

    private void setTableResult(TableResultLoader tableResult) {
        this.tableResult = tableResult;
    }


    @Override
    protected Optional<TableStructDef> fetchData(String key) {

        List<TableStructDef> list = tableResult.load();
        for (TableStructDef tableStructDef : list) {
            if (tableStructDef.getTableName().equalsIgnoreCase(key)) {
                put(tableStructDef.getClassName(), tableStructDef);
                return Optional.of(tableStructDef);
            } else if (tableStructDef.getClassName().equalsIgnoreCase(key)) {
                put(tableStructDef.getTableName(), tableStructDef);
                return Optional.of(tableStructDef);
            }
        }
        log.warn("no data to load by key=[{}]", key);
        return Optional.empty();


    }

    @Override
    public Optional<TableStructDef> get(String key) {
        try {
            return getValue(key);
        } catch (Exception e) {
            log.error("key={}获取数据异常", key, e);
            throw new IllegalStateException(e);
        }
    }
}