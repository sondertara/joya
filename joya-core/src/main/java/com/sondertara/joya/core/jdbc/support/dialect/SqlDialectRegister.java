/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.support.dialect;



import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.provider.Db2Dialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.DerbyDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.DmDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.H2Dialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.HSQLDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.HiveDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.InformixDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.KingbaseDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.MariaDBDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.MySqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.OracleDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.PhoenixDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.PostgreSqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.SqlLiteDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.SqlServerDialect;
import com.sondertara.joya.core.jdbc.support.dialect.provider.XuGuDialect;
import com.sondertara.joya.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DatabaseMetaData;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言管理器
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2020-10-31
 */
public class SqlDialectRegister {
    private static final Map<String, Class<?>> dialectAliasMap = new ConcurrentHashMap<>();
    private static final Map<String, SqlDialect> dialectCache = new ConcurrentHashMap<>();

    static {
        registerDialectAlias(DbType.db2.name(), Db2Dialect.class);
        // Apache Derby
        registerDialectAlias(DbType.derby.name(), DerbyDialect.class);
        registerDialectAlias(DbType.dm.name(), DmDialect.class);
        registerDialectAlias(DbType.h2.name(), H2Dialect.class);
        registerDialectAlias(DbType.hive.name(), HiveDialect.class);
        registerDialectAlias(DbType.informix.name(), InformixDialect.class);
        registerDialectAlias(DbType.hsql.name(), HSQLDialect.class);
//        registerDialectAlias(DbType.i.name(), ImpalaDialect.class);
        registerDialectAlias(DbType.kingbase.name(), KingbaseDialect.class);
        registerDialectAlias(DbType.mariadb.name(), MariaDBDialect.class);
        registerDialectAlias(DbType.mysql.name(), MySqlDialect.class);
        registerDialectAlias(DbType.oracle.name(), OracleDialect.class);
        registerDialectAlias(DbType.phoenix.name(), PhoenixDialect.class);
        registerDialectAlias(DbType.postgresql.name(), PostgreSqlDialect.class);
        registerDialectAlias(DbType.sqlite.name(), SqlLiteDialect.class);
        registerDialectAlias(DbType.sqlserver.name(), SqlServerDialect.class);
        registerDialectAlias(DbType.jtds.name(), SqlServerDialect.class);
        registerDialectAlias(DbType.xugu.name(), XuGuDialect.class);
        registerDialectAlias(DbType.clickhouse.name(), MySqlDialect.class);
    }

    public static void clearDialectCache() {
        dialectCache.clear();
    }

    public static void registerDialectAlias(String dialectName, Class<? extends SqlDialect> dialectClass) {
        dialectAliasMap.put(dialectName, dialectClass);
        dialectAliasMap.put(dialectClass.getName(), dialectClass);
    }

    public static SqlDialect findOrCreate(DbType dbType) {
        return findOrCreate(dbType.name());
    }

    public static PageSqlDialect getDialect(Connection connection) {
        DbType databaseType = JdbcUtils.getDatabaseType(connection);
        return (PageSqlDialect) SqlDialectRegister.findOrCreate(databaseType);
    }

    public static PageSqlDialect findOrCreate(Connection connection) {
        DbType databaseType = JdbcUtils.getDatabaseType(connection);
        return (PageSqlDialect) SqlDialectRegister.findOrCreate(databaseType);
    }
    public static SqlDialect findOrCreate(DatabaseMetaData databaseMetaData) {
        try {
            return findOrCreate(JdbcUtils.getDatabaseType(databaseMetaData.getConnection()));
        } catch (SQLException e) {
            throw new com.sondertara.joya.exceptions.DbException(e);
        }
    }

    public static SqlDialect findOrCreate(String dialectName) {
        if (Strings.isBlank(dialectName)) {
            return DefaultSqlDialect.DEFAULT;
        }
        SqlDialect dialect = dialectCache.get(dialectName);
        if (dialect != null) {
            return dialect;
        }
        Class<?> aClass = dialectAliasMap.get(dialectName);
        if (aClass == null) {
            try {
                aClass = Class.forName(dialectName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Dialect class not found: " + dialectName, e);
            }
        }
        if (aClass != null) {
            try {
                dialect = (SqlDialect) aClass.newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("load dialect '" + aClass.getName() + "' failed, " + e.getMessage(), e);
            }
        }
        //
        dialectCache.put(dialectName, dialect);
        return dialect;
    }
}
