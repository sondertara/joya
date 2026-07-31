package com.sondertara.joya.datasource;


import javax.sql.DataSource;

/**
 * @author huangxiaohu.1ih
 * @date 2024/10/30 11:33
 */

public interface DataSourceFactory  {

    DataSource createDataSource(DataSourceProperties properties);
   default String getName() {
        return "default";
    }
}
