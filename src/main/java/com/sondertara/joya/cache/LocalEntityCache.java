package com.sondertara.joya.cache;

import com.sondertara.joya.core.data.AbstractTableResult;
import com.sondertara.joya.core.data.EntityManagerTableResultAdapter;
import com.sondertara.joya.core.model.TableStruct;
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
public class LocalEntityCache extends GuavaAbstractLoadingCache<String, TableStruct>
    implements ILocalCache<String, TableStruct> {

  private static final Logger log = LoggerFactory.getLogger(LocalEntityCache.class);

  private static volatile LocalEntityCache cache = null;

  private LocalEntityCache() {
    setMaximumSize(1000);
    setExpireAfterWriteDuration(60 * 5);
  }

  private AbstractTableResult tableResult;

  public static synchronized LocalEntityCache getInstance() {
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

  public void setTableResult(AbstractTableResult tableResult) {
    this.tableResult = tableResult;
  }

  @Override
  protected TableStruct fetchData(String key) {

    List<TableStruct> list = tableResult.load();
    for (TableStruct tableStruct : list) {
      if (tableStruct.getTableName().equalsIgnoreCase(key)) {
        put(tableStruct.getClassName(), tableStruct);
        return tableStruct;
      } else if (tableStruct.getClassName().equalsIgnoreCase(key)) {
        put(tableStruct.getTableName(), tableStruct);
        return tableStruct;
      }
    }
    log.warn("no data to load by key=[{}]", key);
    return null;
  }

  @Override
  public Optional<TableStruct> get(String key) {
    TableStruct value = null;
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
