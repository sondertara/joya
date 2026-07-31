
package com.sondertara.joya.migration.service;


import com.sondertara.common.io.IOUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.timing.Stopwatch;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.migration.exchange.AbstractBatchExchanger;
import com.sondertara.joya.migration.exchange.calculate.PrintablePerfStat;
import com.sondertara.joya.migration.exchange.domain.ExchangeDatasourceConfiguration;
import com.sondertara.joya.migration.exchange.domain.GlobalParamConfigProperties;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.exchange.robot.RobotWriter;
import com.sondertara.joya.migration.utils.JdbcTypesUtils;
import com.sondertara.joya.migration.utils.ProductTypeUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 数据迁移主逻辑类
 *
 * @author tang
 */
@Slf4j
public class MigrationService implements Runnable {

    /**
     * 性能统计记录表
     */
    @Getter
    private final List<PrintablePerfStat> perfStats = new ArrayList<>();

    /**
     * 配置参数
     */
    private final ExchangeDatasourceConfiguration configuration;
    private final Executor readExecutor;
    private final Executor writeExecutor;

    private RobotReader robotReader;
    private RobotWriter robotWriter;


    /**
     * 构造函数
     *
     * @param properties 配置信息
     */
    public MigrationService(ExchangeDatasourceConfiguration properties,
                            Executor tableReadExecutor,
                            Executor tableWriteExecutor) {
        this.configuration = Objects.requireNonNull(properties, "properties is null");
        this.readExecutor = Objects.requireNonNull(tableReadExecutor, "tableReadExecutor is null");
        this.writeExecutor = Objects.requireNonNull(tableWriteExecutor, "tableWriteExecutor is null");
    }


    /**
     * 中断执行中的任务
     */
    synchronized public void interrupt() {
        if (null != robotReader) {
            robotReader.interrupt();
        }
        if (null != robotWriter) {
            robotWriter.interrupt();
        }
    }

    /**
     * 执行入口
     */
    @Override
    public void run() {
        doRun();
    }

    /**
     * 执行主逻辑
     */
    private void doRun() {
        log.info("dbswitch data service is started....");

        GlobalParamConfigProperties globalParam = configuration.getConfig();
        int maxQueueSize = globalParam.getChannelQueueSize();
        int writeThreadNum = globalParam.getWriteThreadNum();
        boolean concurrentWrite = JdbcTypesUtils.supportConcurrentWrite(configuration.getTarget());
        Throwable globalThrowable = null;
        Stopwatch stopwatch = Stopwatch.createStarted();

        AbstractBatchExchanger exchanger = new DefaultBatchExchanger(readExecutor, writeExecutor, maxQueueSize, perfStats);
        DataSource targetDataSource = null;
        DataSource sourceDataSource = null;
        try {
            targetDataSource = DataSourceUtils.createTargetDataSource(configuration.getTarget());
            sourceDataSource = DataSourceUtils.createSourceDataSource(configuration.getSource());
            robotReader = new DefaultReaderRobot(configuration, sourceDataSource, targetDataSource);
            robotWriter = new DefaultWriterRobot(robotReader, writeThreadNum, concurrentWrite);
            boolean success = executeSqlScripts(targetDataSource, configuration.getTarget().getBeforeSqlScripts());
            try {
                exchanger.exchange(robotReader, robotWriter);
            } finally {
                if (success) {
                    executeSqlScripts(targetDataSource, configuration.getTarget().getAfterSqlScripts());
                }
            }
        } catch (Throwable t) {
            globalThrowable = t;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new RuntimeException(t);
        } finally {
            stopwatch.stop();
            log.info("total ellipse = {} s", stopwatch.elapsedMillis());
            ProductTypeUtils.tryCloseDataSource(targetDataSource);
            ProductTypeUtils.tryCloseDataSource(sourceDataSource);

            if (!perfStats.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("=====================================\n");
                sb.append(String.format("total ellipse time:\t %d s\n", stopwatch.elapsedMillis()));
                sb.append("-------------------------------------\n");
                perfStats.forEach(st -> sb.append(st.getPrintableString()));
                sb.append("=====================================\n");
                log.info("\n\n" + sb.toString());
            } else if (null != globalThrowable) {
                log.error("error:", globalThrowable);
            } else {
                log.error("!!!!!!!!!!Internal Error!!!!!!!");
            }

        }
    }

    private boolean executeSqlScripts(DataSource targetDataSource, String sqlScripts) {
        if (StringUtils.isBlank(sqlScripts) || StringUtils.isBlank(sqlScripts.trim())) {
            return true;
        }

        try {

            JoyaJdbc joyaJdbc = new JoyaJdbc(targetDataSource);

            joyaJdbc.runScript(IOUtils.getUtf8Reader(new ByteArrayInputStream(sqlScripts.getBytes(StandardCharsets.UTF_8))));
            return true;
        } catch (Throwable t) {
            log.error("Failed to execute sql script: {}", t.getMessage(), t);
            return false;
        }

    }
}
