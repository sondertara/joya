
package com.sondertara.joya.migration.utils;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

@Slf4j
public final class ProductTypeUtils {

    public static boolean isGreenplum(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT version()")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String version = resultSet.getString(1);
                    if (Objects.nonNull(version) && version.contains("Greenplum")) {
                        if (log.isDebugEnabled()) {
                            log.debug("#### Target database is Greenplum Cluster");
                        }
                        return true;
                    }
                }
            }
        } catch (SQLException sqlException) {
            if (log.isDebugEnabled()) {
                log.debug("#### Failed to execute sql :select version(), and guesses it is not Greenplum datasource!");
            }
        }
        return false;
    }

    public static boolean isStarRocks(Connection connection) {
        // 此查询语句是StarRocks查询be节点是否存活，可以用来判断是否是StarRocks数据源
        String sql = "SHOW BACKENDS";
        try (Statement statement = connection.createStatement()) {
            return statement.execute(sql);
        } catch (SQLException sqlException) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to execute sql :show backends, and guesses it is mysql datasource!");
            }
        }
        return false;
    }

    public static boolean isOceanBaseUseMysqlMode(Connection connection) {
        String sql = "show global variables where variable_name = 'ob_compatibility_mode'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String value = resultSet.getString(2);
                    if (Objects.nonNull(value)) {
                        if (value.toUpperCase().contains("MYSQL")) {
                            return true;
                        } else {
                            return false;
                        }
                    } else {
                        throw new RuntimeException();
                    }
                } else {
                    throw new RuntimeException();
                }
            }
        } catch (SQLException sqlException) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to execute sql :{}, and guesses OceanBase is MySQL Mode!", sql);
            }
        }
        return true;
    }

    public static void tryCloseDataSource(@Nullable DataSource dataSource) {
        if (null==dataSource){
            return;
        }
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception e) {
                log.error("Close datasource error,{}", e.getMessage());
            }
        }
    }

}
