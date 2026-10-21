package com.sondertara.joya.core.constant;

import com.sondertara.joya.core.cache.CacheHolder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * @author huangxiaohu
 */

@Data
@Accessors(chain = true)
public class JoyaConfig implements Serializable {

    private boolean columnUppercase;
    private boolean tableUppercase;

    private int maxJoinTable = 10;

    private String[] packageToScan;

    private int batchSize = 2000;

    /**
     * schema 校验策略：NONE / WARN / THROW
     */
    private SchemaValidation schemaValidation = SchemaValidation.NONE;

    /**
     * 数据库元数据缓存过期时间（秒），默认 300s
     */
    private int schemaCacheTtl = 300;

    public JoyaConfig setColumnUppercase(boolean columnUppercase) {
        this.columnUppercase = columnUppercase;
        CacheHolder.clearAll();
        return this;
    }

    public JoyaConfig setTableUppercase(boolean tableUppercase) {
        this.tableUppercase = tableUppercase;
        CacheHolder.clearAll();
        return this;
    }

    /**
     * clean cache by manual
     */
    public void cacheClean() {
        CacheHolder.clearAll();
    }

    /**
     * JVM 级单例，用作过渡桥（阶段0引入，阶段6退役）。
     * 消除 {@code JoyaClient.config()} 这条不存在的静态方法导致的编译错误。
     */
    private static final JoyaConfig INSTANCE = new JoyaConfig();

    /**
     * 获取 JVM 级单例配置。过渡期使用；后续改造为实例级配置后此方法退役。
     *
     * @return 全局配置单例
     */
    public static JoyaConfig instance() {
        return INSTANCE;
    }
}
