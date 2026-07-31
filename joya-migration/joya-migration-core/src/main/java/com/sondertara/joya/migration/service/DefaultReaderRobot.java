
package com.sondertara.joya.migration.service;

import com.sondertara.common.collection.LinkedCaseInsensitiveSet;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.collection.StreamUtils;
import com.sondertara.common.json.JsonUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.core.jdbc.support.meta.TableType;
import com.sondertara.joya.migration.exchange.domain.ExchangeDatasourceConfiguration;
import com.sondertara.joya.migration.exchange.domain.ReaderTaskParam;
import com.sondertara.joya.migration.exchange.domain.ReaderTaskResult;
import com.sondertara.joya.migration.exchange.domain.SourceEndpointProperties;
import com.sondertara.joya.migration.exchange.handler.ReaderTaskThread;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.ProductProviderFactory;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 源端数据库表并发读取控制
 *
 * @author tang
 */
@Slf4j
public class DefaultReaderRobot extends RobotReader<ReaderTaskResult> {

    private ExchangeDatasourceConfiguration configuration;
    private DataSource sourceDataSource;
    private DataSource targetDataSource;
    private List<Supplier> readTaskThreads;
    private List<CompletableFuture> futures;
    private Executor threadExecutor;
    private final AtomicInteger taskCount;

    private final ProductFactoryProvider sourceFactoryProvider;

    private final ProductFactoryProvider targetFactoryProvider;

    public DefaultReaderRobot(
            ExchangeDatasourceConfiguration configuration,
            DataSource sourceDataSource,
            DataSource targetDataSource) {
        this.configuration = configuration;
        this.sourceDataSource = sourceDataSource;
        this.targetDataSource = targetDataSource;

        this.sourceFactoryProvider = ProductProviderFactory.newProvider(configuration.getSource().getDbType(), sourceDataSource);
        this.targetFactoryProvider = ProductProviderFactory.newProvider(configuration.getTarget().getDbType(), targetDataSource);
        this.taskCount = new AtomicInteger(0);
    }

    @Override
    public void interrupt() {
        Optional.ofNullable(futures).orElseGet(ArrayList::new).forEach(f -> f.cancel(true));
        this.clearChannel();
    }

    @Override
    public boolean init(Executor threadExecutor) {
        this.threadExecutor = threadExecutor;
        this.readTaskThreads = new ArrayList<>();
        MetaDataService tdsService = ProductProviderFactory.crateMetaService(targetFactoryProvider);
        Set<String> targetExistTables = tdsService.queryTableList(configuration.getTarget().getTargetSchema())
                .stream().map(TableDescription::getTableName).collect(Collectors.toSet());
        List<TableDescription> tableDescriptions = splitReaderTask();
        this.taskCount.set(tableDescriptions.size());
        tableDescriptions.forEach(t -> addReaderTask(t, targetExistTables));
        return true;
    }

    private void addReaderTask(TableDescription tableDescription, Set<String> targetExistTables) {
        ReaderTaskParam param = ReaderTaskParam.builder()
                .memChannel(this.getChannel())
                .tableDescription(tableDescription)
                .configuration(this.configuration)
                .sourceDataSource(sourceDataSource)
                .targetDataSource(targetDataSource)
                .targetExistTables(targetExistTables)
                .build();
        ReaderTaskThread readerTaskThread = new ReaderTaskThread(param);
        readerTaskThread.onSuccess(s -> {
            taskCount.decrementAndGet();
        });
        this.readTaskThreads.add(readerTaskThread);

    }

    private List<TableDescription> splitReaderTask() {
        List<TableDescription> tableDescriptions = new ArrayList<>();

        SourceEndpointProperties sourceProperties = configuration.getSource();
        MetaDataService sourceMetaDataService = ProductProviderFactory.crateMetaService(sourceFactoryProvider);


        TableType syncTableType = TableType.ofCode(sourceProperties.getTableType());
        // 判断处理的策略：是排除还是包含
        Set<String> includes =
                Arrays.stream(StringUtils.split(sourceProperties.getSourceIncludes(), StringUtils.COMMA)).map(String::trim).collect((Supplier<Set<String>>) LinkedCaseInsensitiveSet::new, Set::add, Set::addAll);
        log.info("Includes tables is :{}", JsonUtils.toJsonString(includes));
        List<String> filters =
                Arrays.stream(StringUtils.split(sourceProperties.getSourceExcludes(), StringUtils.COMMA)).map(String::trim).collect(Collectors.toList());
        log.info("Filter tables is :{}", JsonUtils.toJsonString(filters));

        boolean useExcludeTables = includes.isEmpty();
        if (useExcludeTables) {
            log.info("!!!! Use dbswitch.source.source-excludes parameter to filter tables");
        } else {
            log.info("!!!! Use dbswitch.source.source-includes parameter to filter tables");
        }

        List<String> schemas =
                StreamUtils.of(StringUtils.split(sourceProperties.getSourceSchema(), StringUtils.COMMA))
                        .collect(Collectors.toList());
        log.info("Source schema names is :{}", JsonUtils.toJsonString(schemas));

        for (String schema : schemas) {
            List<TableDescription> tableList = sourceMetaDataService.queryTableList(schema);
            if (tableList.isEmpty()) {
                log.warn("### Find source database table list empty for schema name is : {}", schema);
            } else {
                for (TableDescription td : tableList) {
                    // 当没有配置迁移的表名时，默认为根据类型同步所有
                    if (includes.isEmpty()) {
                        if (null == syncTableType) {
                            tableDescriptions.add(td);
                        } else if (td.getTableType().equals(syncTableType)) {
                            tableDescriptions.add(td);
                        }
                    } else if (td.getTableType().equals(syncTableType)) {
                        String tableName = td.getTableName();
                        if (useExcludeTables) {
                            if (!filters.contains(tableName)) {
                                tableDescriptions.add(td);
                            }
                        } else {
                            //当只有一个时，如果是正则则用正则表达式
                            if (includes.size() == 1) {
                                String next = includes.iterator().next();
                                if (next.contains("*") || next.contains("?")) {
                                    if (Pattern.matches(next, tableName)) {
                                        tableDescriptions.add(td);
                                    }
                                } else if (tableName.equalsIgnoreCase(next)) {
                                    tableDescriptions.add(td);
                                }
                            } else if (includes.contains(tableName)) {
                                tableDescriptions.add(td);
                            }
                        }
                    }
                }
            }
        }
        return tableDescriptions;
    }

    @Override
    public void startup() {
        futures = new ArrayList<>(readTaskThreads.size());
        readTaskThreads.forEach(
                task ->
                        futures.add(CompletableFuture.supplyAsync(task, threadExecutor))
        );
    }

    @Override
    public long getRemainingCount() {
        return taskCount.get();
    }

    @Override
    public Optional<ReaderTaskResult> getWorkResult() {
        return futures.stream().map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .map(f -> (ReaderTaskResult) f)
                .peek(f -> f.padding())
                .reduce(
                        (r1, r2) -> {
                            Map<String, Long> perf = Maps.newHashMap(r1.getPerf());
                            if (r2.getSuccessCount() > 0) {
                                perf.put(r2.getTableNameMapString(), r2.getRecordCount());
                            }
                            Map<String, Throwable> except = Maps.newHashMap(r1.getExcept());
                            if (r2.getExcept().size() > 0) {
                                except.putAll(r2.getExcept());
                            }
                            return ReaderTaskResult.builder()
                                    .perf(perf)
                                    .except(except)
                                    .successCount(r1.getSuccessCount() + r2.getSuccessCount())
                                    .failureCount(r1.getFailureCount() + r2.getFailureCount())
                                    .recordCount(r1.getRecordCount() + r2.getRecordCount())
                                    .totalBytes(r1.getTotalBytes() + r2.getTotalBytes())
                                    .throwable(Objects.nonNull(r1.getThrowable()) ? r1.getThrowable() : r2.getThrowable())
                                    .build();
                        }
                );
    }

    @Override
    public void shutdown() {

    }
}
