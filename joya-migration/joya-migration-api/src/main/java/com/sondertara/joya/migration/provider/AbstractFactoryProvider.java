
package com.sondertara.joya.migration.provider;


import com.sondertara.common.base.Assert;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.JdbcUtils;

import javax.sql.DataSource;
import java.sql.Connection;

public abstract class AbstractFactoryProvider implements ProductFactoryProvider {

    private DataSource dataSource;
    private DbType dbType;
    private SqlDialect sqlDialect;

    protected AbstractFactoryProvider(DataSource dataSource) {
        Assert.notNull(dataSource);
        this.dataSource = dataSource;
        dbType = DbType.of(getName());
        sqlDialect = SqlDialectRegister.findOrCreate(this.dbType);


    {
        try (Connection connection = dataSource.getConnection()) {
            this.dbType = JdbcUtils.getDatabaseType(connection);
            sqlDialect = SqlDialectRegister.findOrCreate(this.dbType);
        } catch (Exception e) {
            throw new DbException(e);
        }
    }

}

    @Override
    public DataSource getDataSource() {
        return this.dataSource;
    }

    @Override
    public SqlDialect getSqlDialect() {
        return sqlDialect;
    }

    @Override
    public final DbType getProductType() {
        return this.dbType;

    }

}
