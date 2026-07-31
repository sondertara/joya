package com.sondertara.joya.migration;

import com.sondertara.common.concurrent.ThreadUtils;
import com.sondertara.common.concurrent.threadpool.ThreadPoolFactory;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.migration.consts.CaseConvertEnum;
import com.sondertara.joya.migration.exchange.domain.ExchangeDatasourceConfiguration;
import com.sondertara.joya.migration.exchange.domain.GlobalParamConfigProperties;
import com.sondertara.joya.migration.exchange.domain.SourceEndpointProperties;
import com.sondertara.joya.migration.exchange.domain.TargetEndpointProperties;
import com.sondertara.joya.migration.service.MigrationService;
import oracle.jdbc.driver.OracleDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TODO
 *
 * @author ${USER}
 * @date ${DATE} ${TIME}
 */
public class Main {
    public static void main(String[] args) throws Exception {

        ExchangeDatasourceConfiguration configuration = new ExchangeDatasourceConfiguration();
        SourceEndpointProperties sourceEndpointProperties = new SourceEndpointProperties();
        sourceEndpointProperties.setDbType(DbType.oracle);
        sourceEndpointProperties.setUrl("jdbc:oracle:thin:@//128.192.23.72:11521/iompdb");
        sourceEndpointProperties.setDriverClassName(OracleDriver.class.getName());
        sourceEndpointProperties.setUsername("cpms");
        sourceEndpointProperties.setPassword("cpms");
        sourceEndpointProperties.setSourceSchema("CPMS");
        List<String> tableList = new ArrayList<>();
        tableList.add("COLL_OBJTYPE_INFO");
//        tableList.add("COLL_INF_CONFIG");
//        tableList.add("COLL_INIT_TASK");
//        tableList.add("EVALUATION_SUGGESTION,EVALUATION_THRESHOLD");
//        tableList.add("MSG_EMAIL_CONFIG");
//        tableList.add("TABLE_SYNC_TASK_CONFIG,TABLE_SYNC_TASK_RECORD");

        //"COLL_INF_CONFIG,COLL_OBJTYPE_INFO,COLL_INIT_TASK,EVALUATION_SUGGESTION,EVALUATION_THRESHOLD,MSG_EMAIL_CONFIG,TABLE_SYNC_TASK_CONFIG,TABLE_SYNC_TASK_RECORD"

        sourceEndpointProperties.setSourceIncludes(String.join(",", tableList));
        sourceEndpointProperties.setSourceExcludes("");
        sourceEndpointProperties.setRegexTableMapper(new ArrayList<>());
        sourceEndpointProperties.setRegexColumnMapper(new ArrayList<>());


        TargetEndpointProperties targetEndpointProperties = new TargetEndpointProperties();
        targetEndpointProperties.setDbType(DbType.mysql);
        targetEndpointProperties.setPassword("mysqL#12");
        targetEndpointProperties.setUsername("root");
        targetEndpointProperties.setDriverClassName("com.mysql.cj.jdbc.Driver");
        targetEndpointProperties.setUrl("jdbc:mysql://128.192.116.79:13306/capa?characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&autoReconnect=true&failOverReadOnly=false&autoReconnectForPools=true&allowMultiQueries=true&allowPublicKeyRetrieval=true");
        targetEndpointProperties.setTargetSchema("capa");
        targetEndpointProperties.setTableNameCase(CaseConvertEnum.LOWER);
        targetEndpointProperties.setColumnNameCase(CaseConvertEnum.LOWER);
        targetEndpointProperties.setTargetDrop(true);
        targetEndpointProperties.setOnlyCreate(false);
        targetEndpointProperties.setChangeDataSync(true);
        targetEndpointProperties.setCreateTableAutoIncrement(true);
        targetEndpointProperties.setWriterEngineInsert(false);
        GlobalParamConfigProperties globalParamConfigProperties = new GlobalParamConfigProperties();
        configuration.setSource(sourceEndpointProperties);
        configuration.setTarget(targetEndpointProperties);
        configuration.setConfig(globalParamConfigProperties);

        ExecutorService readExecutor = ThreadPoolFactory.getInstance().newFixedThreadPool("Read", 4);
        ExecutorService writeExecutor = ThreadPoolFactory.getInstance().newFixedThreadPool("Write", 4);

        MigrationService migrationService = new MigrationService(configuration, readExecutor, writeExecutor);
        migrationService.run();


        ThreadPoolFactory.getInstance().shutdown("Write");
        ThreadPoolFactory.getInstance().shutdown("Read");

        Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        System.out.println("Finish test");


    }
}