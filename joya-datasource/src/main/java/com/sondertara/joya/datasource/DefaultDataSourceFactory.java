package com.sondertara.joya.datasource;


import javax.sql.DataSource;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/30 14:20
 */
public class DefaultDataSourceFactory implements DataSourceFactory {
    @Override
    public DataSource createDataSource(DataSourceProperties properties) {

        return new JoyaDataSource(properties);
    }

    @Override
    public String getName() {
        return "default";
    }
}
