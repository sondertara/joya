package com.sondertara.joya.core.jdbc.support;


import com.sondertara.joya.exceptions.DbException;

import java.util.Locale;

/**
 * @author huangxiaohu
 */

public enum DbType {
    /**
     *
     */
    other(1),
    jtds(1 << 1),
    hsql(1 << 2),
    db2(1 << 3),
    postgresql(1 << 4),

    sqlserver(1 << 5),
    oracle(1 << 6),
    mysql(1 << 7),
    mariadb(1 << 8),
    derby(1 << 9),

    hive(1 << 10),
    h2(1 << 11),
    /**
     * dm.jdbc.driver.DmDriver
     */
    dm(1 << 12),
    kingbase(1 << 13),
    gbase(1 << 14),

    oceanbase(1 << 15),
    informix(1 << 16),
    odps(1 << 17),
    teradata(1 << 18),
    phoenix(1 << 19),

    edb(1 << 20),
    /**
     * org.apache.kylin.jdbc.Driver
     */
    kylin(1 << 21),
    sqlite(1 << 22),
    ads(1 << 23),
    presto(1 << 24),
    /**
     * com.alibaba.xdriver.elastic.jdbc.ElasticDriver
     */
    elastic_search(1 << 25),
    hbase(1 << 26),
    drds(1 << 27),

    clickhouse(1 << 28),
    blink(1 << 29),
    antspark(1 << 30),
    oceanbase_oracle(1L << 31),
    polardb(1L << 32),

    ali_oracle(1L << 33),
    mock(1L << 34),
    sybase(1L << 35),
    highgo(1L << 36),
    /**
     * 非常成熟的开源mpp数据库
     */
    greenplum(1L << 37),
    /**
     * 华为的mpp数据库
     */
    gaussdb(1L << 38),

    trino(1L << 39),

    oscar(1L << 40),

    tidb(1L << 41),

    tydb(1L << 42),

    ingres(0),
    cloudscape(0),
    timesten(0),
    as400(0),
    sapdb(0),
    kdb(0),
    log4jdbc(0),
    xugu(0),
    firebirdsql(0),
    JSQLConnect(0),
    JTurbo(0),
    interbase(0),
    pointbase(0),
    edbc(0),
    mimer(0);

    public final long mask;

    private DbType(long mask) {
        this.mask = mask;
    }

    public static long of(DbType... types) {
        long value = 0;

        for (DbType type : types) {
            value |= type.mask;
        }

        return value;
    }

    public static DbType of(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("database name must by not null");
        }

        if ("aliyun_ads".equalsIgnoreCase(name)) {
            return ads;
        }

        try {
            return valueOf(name.toLowerCase(Locale.US));
        } catch (Exception e) {
            throw new DbException(e);
        }
    }

    public final boolean equals(String other) {
        return this == of(other);
    }

    /**
     * *
     * 类似于PostgreSQL系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikePostgres() {
        return this == postgresql || this == kingbase || this == gaussdb;
    }

    /**
     * 类似于MySQL系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeMysql() {
        return this == mysql || this == mariadb || this == gbase;
    }

    /**
     * 类似于Oracle系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeOracle() {
        return this == oracle || this == dm;
    }

    /**
     * 类似于SQL Server系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeSqlServer() {
        return this == sqlserver || this == sybase;
    }

    /**
     * 类似于Hive系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeHive() {
        return this == hive;
    }


    /**
     * 是否为ClickHouse数据库类型
     *
     * @return boolean
     */
    public boolean isClickHouse() {
        return this == clickhouse;
    }
    /**
     * 是否为MongoDB数据库类型
     *
     * @return boolean
     */
    public boolean isMongodb() {
        return false;
    }
}
