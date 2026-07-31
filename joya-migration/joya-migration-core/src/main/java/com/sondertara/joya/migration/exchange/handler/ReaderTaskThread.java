
package com.sondertara.joya.migration.exchange.handler;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.LinkedCaseInsensitiveSet;
import com.sondertara.common.collection.Lists;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.text.unit.DataSizeUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ResultSetWrapper;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.SourceProperties;
import com.sondertara.joya.migration.consts.CaseConvertEnum;
import com.sondertara.joya.migration.consts.Constants;
import com.sondertara.joya.migration.exceptions.ExchangeException;
import com.sondertara.joya.migration.exchange.BatchElement;
import com.sondertara.joya.migration.exchange.MemChannel;
import com.sondertara.joya.migration.exchange.calculate.DefaultChangeCalculatorService;
import com.sondertara.joya.migration.exchange.calculate.RecordRowChangeCalculator;
import com.sondertara.joya.migration.exchange.calculate.RecordRowHandler;
import com.sondertara.joya.migration.exchange.calculate.RowChangeTypeEnum;
import com.sondertara.joya.migration.exchange.calculate.TaskParamEntity;
import com.sondertara.joya.migration.exchange.domain.ExchangeDatasourceConfiguration;
import com.sondertara.joya.migration.exchange.domain.ReaderTaskParam;
import com.sondertara.joya.migration.exchange.domain.ReaderTaskResult;
import com.sondertara.joya.migration.exchange.domain.SourceEndpointProperties;
import com.sondertara.joya.migration.exchange.domain.TargetEndpointProperties;
import com.sondertara.joya.migration.exchange.task.TaskProcessor;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.ProductProviderFactory;
import com.sondertara.joya.migration.provider.manage.TableManageProvider;
import com.sondertara.joya.migration.provider.meta.MetadataProvider;
import com.sondertara.joya.migration.provider.query.TableDataQueryProvider;
import com.sondertara.joya.migration.provider.transform.RecordTransformProvider;
import com.sondertara.joya.migration.provider.write.TableDataWriteProvider;
import com.sondertara.joya.migration.service.MetaDataService;
import com.sondertara.joya.migration.utils.JdbcTypesUtils;
import com.sondertara.joya.migration.utils.PatterNameUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 数据读取线程体（一个表的读）
 *
 * @author tang
 */
@Slf4j
public class ReaderTaskThread extends TaskProcessor<ReaderTaskResult> {

    private final long MAX_CACHE_BYTES_SIZE = 128 * 1024 * 1024;

    private final ExchangeDatasourceConfiguration properties;
    private final SourceEndpointProperties sourceProperties;
    private final TargetEndpointProperties targetProperties;
    private int fetchSize = Constants.MINIMUM_FETCH_SIZE;
    private TableDescription tableDescription;
    private MemChannel memChannel;

    // 来源端
    private final DataSource sourceDataSource;
    private MetaDataService sourceMetaDataService;
    private DbType sourceProductType;
    private String sourceSchemaName;
    private String sourceTableName;
    private String sourceTableRemarks;
    private List<ColumnDescription> sourceColumnDescriptions;
    private List<String> sourcePrimaryKeys;

    // 目的端
    private final DataSource targetDataSource;
    private DbType targetProductType;
    private Set<String> targetExistTables;
    private String targetSchemaName;
    private String targetTableName;
    private List<ColumnDescription> targetColumnDescriptions;
    private List<String> targetPrimaryKeys;

    // 日志输出字符串使用
    private String tableNameMapString;

    // 统计信息
    AtomicLong totalBytes = new AtomicLong(0);
    AtomicLong totalCount = new AtomicLong(0);

    private ProductFactoryProvider sourceFactoryProvider;
    private ProductFactoryProvider targetFactoryProvider;

    public ReaderTaskThread(ReaderTaskParam taskParam) {
        this.sourceDataSource = taskParam.getSourceDataSource();
        this.targetDataSource = taskParam.getTargetDataSource();
        this.tableDescription = taskParam.getTableDescription();
        this.memChannel = taskParam.getMemChannel();
        this.properties = taskParam.getConfiguration();
        this.sourceProperties = this.properties.getSource();
        this.targetProperties = this.properties.getTarget();
        this.sourceProductType = this.sourceProperties.getDbType();
        this.targetProductType = this.targetProperties.getDbType();
        this.sourceSchemaName = this.sourceProperties.getSourceSchema();
        this.sourceTableName = this.tableDescription.getTableName();
        this.targetExistTables = taskParam.getTargetExistTables();
    }

    @Override
    protected void beforeProcess() {
        if (sourceProperties.getFetchSize() >= fetchSize) {
            fetchSize = sourceProperties.getFetchSize();
        }


        if (this.targetProductType.isLikeHive()) {
            // !! hive does not support upper table name and upper column name
            targetProperties.setTableNameCase(CaseConvertEnum.LOWER);
            targetProperties.setColumnNameCase(CaseConvertEnum.LOWER);
        }

        // 获取映射转换后新的表名
        this.targetSchemaName = targetProperties.getTargetSchema();
        this.targetTableName = targetProperties
                .getTableNameCase()
                .convert(
                        PatterNameUtils.getFinalName(
                                tableDescription.getTableName(),
                                sourceProperties.getRegexTableMapper()
                        )
                );

        if (StringUtils.isEmpty(this.targetTableName)) {
            throw new ExchangeException("表名的映射规则配置有误，不能将[" + this.sourceTableName + "]映射为空");
        }

        this.tableNameMapString = String.format("%s.%s --> %s.%s",
                tableDescription.getSchema(), tableDescription.getTableName(),
                targetSchemaName, targetTableName);

        sourceFactoryProvider = ProductProviderFactory
                .newProvider(sourceProductType, sourceDataSource);
        targetFactoryProvider = ProductProviderFactory
                .newProvider(targetProductType, targetDataSource);
    }

    @Override
    protected ReaderTaskResult doProcess() {
        log.info("Begin Migrate table for {}", tableNameMapString);

        this.sourceMetaDataService = ProductProviderFactory.crateMetaService(sourceFactoryProvider);

        // 读取源表的表及字段元数据
        this.sourceTableRemarks = sourceMetaDataService
                .getTableRemark(sourceSchemaName, sourceTableName);
        checkInterrupt();
        this.sourceColumnDescriptions = sourceMetaDataService
                .queryTableColumnMeta(sourceSchemaName, sourceTableName);
        checkInterrupt();
        this.sourcePrimaryKeys = sourceMetaDataService
                .queryTablePrimaryKeys(sourceSchemaName, sourceTableName);
        checkInterrupt();

        // 根据表的列名映射转换准备目标端表的字段信息
        this.targetColumnDescriptions = sourceColumnDescriptions.stream()
                .map(column -> {
                    String newName = targetProperties.getColumnNameCase()
                            .convert(
                                    PatterNameUtils.getFinalName(
                                            column.getColumnName(),
                                            sourceProperties.getRegexColumnMapper())
                            );
                    ColumnDescription description = column.copy();
                    description.setColumnName(newName);
                    description.setLabelName(newName);
                    return description;
                }).collect(Collectors.toList());
        this.targetPrimaryKeys = sourcePrimaryKeys.stream()
                .map(name ->
                        targetProperties.getColumnNameCase()
                                .convert(
                                        PatterNameUtils.getFinalName(
                                                name,
                                                sourceProperties.getRegexColumnMapper())
                                )
                ).collect(Collectors.toList());

        // 打印表名与字段名的映射关系
        List<String> columnMapperPairs = new ArrayList<>();
        Map<String, String> mapChecker = new HashMap<>();
        for (int i = 0; i < sourceColumnDescriptions.size(); ++i) {
            String sourceColumnName = sourceColumnDescriptions.get(i).getColumnName();
            String targetColumnName = targetColumnDescriptions.get(i).getColumnName();
            if (StringUtils.hasLength(targetColumnName)) {
                columnMapperPairs.add(String.format("%s --> %s", sourceColumnName, targetColumnName));
                mapChecker.put(sourceColumnName, targetColumnName);
            } else {
                columnMapperPairs.add(String.format(
                        "%s --> %s",
                        sourceColumnName,
                        String.format("<!Field(%s) is Deleted>", (i + 1))
                ));
            }
        }
        log.info("Mapping relation : \ntable mapper : {}  \ncolumn mapper :\n\t{} ",
                tableNameMapString, String.join("\n\t", columnMapperPairs));
        Set<String> valueSet = new LinkedCaseInsensitiveSet(mapChecker.values());
        if (valueSet.size() <= 0) {
            throw new ExchangeException("字段映射配置有误，禁止通过映射将表所有的字段都删除!");
        }
        if (!valueSet.containsAll(this.targetPrimaryKeys)) {
            throw new ExchangeException("字段映射配置有误，禁止通过映射将表的主键字段删除!");
        }
        if (mapChecker.keySet().size() != valueSet.size()) {
            throw new ExchangeException("字段映射配置有误，禁止将多个字段映射到一个同名字段!");
        }

        checkInterrupt();

        TableDataQueryProvider sourceQuerier = sourceFactoryProvider.createTableDataQueryProvider();
        RecordTransformProvider transformProvider = sourceFactoryProvider.createRecordTransformProvider();
        TableDataWriteProvider targetWriter = targetFactoryProvider.createTableDataWriteProvider(
                targetProperties.getWriterEngineInsert());
        MetadataProvider targetMetaProvider = targetFactoryProvider.createMetadataQueryProvider();
        TableManageProvider targetTableManager = targetFactoryProvider.createTableManageProvider();

        if (sourceProductType.isMongodb()) {
            targetProperties.setTargetDrop(true);
        }

        MetaDataService targetMetaService = ProductProviderFactory.crateMetaService(targetFactoryProvider);

        if (targetProductType.isMongodb() || targetProductType.equals(DbType.elastic_search)) {
            try {
                targetFactoryProvider.createTableManageProvider()
                        .dropTable(targetSchemaName, targetTableName);
                log.info("Target Table {}.{} is exits, drop it now !", targetSchemaName, targetTableName);
            } catch (Exception e) {
                log.info("Target Table {}.{} is not exits, create it!", targetSchemaName, targetTableName);
            }
            return doFullCoverSynchronize(targetWriter, targetTableManager, sourceQuerier, transformProvider);
        } else if (targetProperties.getTargetDrop() || targetProductType.isLikeHive()) {
      /*
        如果配置了dbswitch.target.datasource-target-drop=true时，
        <p>
        先执行drop table语句，然后执行create table语句
       */

            try {
                targetFactoryProvider.createTableManageProvider()
                        .dropTable(targetSchemaName, targetTableName);
                log.info("Target Table {}.{} is exits, drop it now !", targetSchemaName, targetTableName);
            } catch (Exception e) {
                log.info("Target Table {}.{} is not exits, create it!", targetSchemaName, targetTableName);
            }

            // 生成建表语句并创建
            List<String> sqlCreateTable = targetMetaService.getDDLCreateTableSQL(
                    targetMetaProvider,
                    targetColumnDescriptions.stream()
                            .filter(column -> StringUtils.hasLength(column.getColumnName()))
                            .collect(Collectors.toList()),
                    targetPrimaryKeys,
                    targetSchemaName,
                    targetTableName,
                    sourceTableRemarks,
                    targetProperties.getCreateTableAutoIncrement(),
                    getTargetTblProperties()
            );

            JoyaJdbc targetJdbcTemplate = new JoyaJdbc(targetDataSource);
            for (String sql : sqlCreateTable) {
                checkInterrupt();
                log.info("Execute SQL: \n{}", sql);
                targetJdbcTemplate.execute(sql);
            }

            // 如果只想创建表，这里直接返回
            if (null != targetProperties.getOnlyCreate()
                    && targetProperties.getOnlyCreate()) {
                return ReaderTaskResult.builder()
                        .tableNameMapString(tableNameMapString)
                        .successCount(1).build();
            }

            if (targetProductType.isLikeHive()) {
                return ReaderTaskResult.builder()
                        .tableNameMapString(tableNameMapString)
                        .successCount(1).build();
            }

            checkInterrupt();
            return doFullCoverSynchronize(targetWriter, targetTableManager, sourceQuerier, transformProvider);
        } else {
            // 对于只想创建表的情况，不提供后续的变化量数据同步功能
            if (null != targetProperties.getOnlyCreate()
                    && targetProperties.getOnlyCreate()) {
                return ReaderTaskResult.builder()
                        .tableNameMapString(tableNameMapString)
                        .successCount(1).build();
            }

            checkInterrupt();

            if (!targetExistTables.contains(targetTableName)) {
                // 当目标端不存在该表时，则生成建表语句并创建
                List<String> sqlCreateTable = targetMetaService.getDDLCreateTableSQL(
                        targetMetaProvider,
                        targetColumnDescriptions.stream()
                                .filter(column -> StringUtils.hasLength(column.getColumnName()))
                                .collect(Collectors.toList()),
                        targetPrimaryKeys,
                        targetSchemaName,
                        targetTableName,
                        sourceTableRemarks,
                        targetProperties.getCreateTableAutoIncrement(),
                        getTblProperties()
                );

                JoyaJdbc targetJdbcTemplate = new JoyaJdbc(targetDataSource);
                for (String sql : sqlCreateTable) {
                    targetJdbcTemplate.execute(sql);
                    log.info("Execute SQL: \n{}", sql);
                }

                checkInterrupt();
                return doFullCoverSynchronize(targetWriter, targetTableManager, sourceQuerier, transformProvider);
            }


            //TODO 如果表字段不存在则新增
            boolean incrementSync = false;
            // 判断是否具备变化量同步的条件：（1）两端表结构一致，且都有一样的主键字段；(2)MySQL使用Innodb引擎；
            if (targetProperties.getChangeDataSync()) {
                // 根据主键情况判断同步的方式：增量同步或覆盖同步
                List<String> dbTargetPks = targetMetaService.queryTablePrimaryKeys(
                        targetSchemaName, targetTableName).stream().map(s -> targetProperties.getColumnNameCase().convert(s)).collect(Collectors.toList());

                if (CollectionUtils.isNotEmpty(targetPrimaryKeys) && CollectionUtils.isNotEmpty(dbTargetPks)) {
                    String left = targetPrimaryKeys
                            .stream()
                            .sorted(Comparator.comparing(String::hashCode))
                            .collect(Collectors.joining());
                    String right = dbTargetPks
                            .stream()
                            .sorted(Comparator.comparing(String::hashCode))
                            .collect(Collectors.joining());
                    if (left.equals(right)) {
                        incrementSync = targetProductType != DbType.mysql
                                || isMysqlInnodbStorageEngine(
                                targetSchemaName, targetTableName, targetDataSource);
                    }
                }
            }
            if (incrementSync) {
                return doIncreaseSynchronize(targetWriter, transformProvider);
            } else {
                return doFullCoverSynchronize(targetWriter, targetTableManager, sourceQuerier, transformProvider);
            }

        }

    }

    /**
     * 执行覆盖同步
     *
     * @param tableWriter
     * @param tableManager
     * @param sourceQuerier
     * @param transformer
     * @return ReaderTaskResult
     */
    private ReaderTaskResult doFullCoverSynchronize(TableDataWriteProvider tableWriter, TableManageProvider tableManager,
                                                    TableDataQueryProvider sourceQuerier, RecordTransformProvider transformer) {
        final int BATCH_SIZE = fetchSize;

        List<String> sourceFields = new ArrayList<>();
        List<String> targetFields = new ArrayList<>();
        for (int i = 0; i < targetColumnDescriptions.size(); ++i) {
            ColumnDescription scd = sourceColumnDescriptions.get(i);
            ColumnDescription tcd = targetColumnDescriptions.get(i);
            if (!StringUtils.isEmpty(tcd.getColumnName())) {
                sourceFields.add(scd.getColumnName());
                targetFields.add(tcd.getColumnName());
            }
        }
        // 准备目的端的数据写入操作
        tableWriter.prepare(targetSchemaName, targetTableName, targetFields, targetPrimaryKeys);

        // 清空目的端表的数据
        tableManager.truncateTableData(targetSchemaName, targetTableName);

        // 查询源端数据并写入目的端
        sourceQuerier.setQueryFetchSize(BATCH_SIZE);

        ResultSetWrapper srs = sourceQuerier.queryTableData(
                sourceSchemaName, sourceTableName, sourceFields
        );

        List<Object[]> cache = new LinkedList<>();
        long cacheBytes = 0;
        try (ResultSet rs = srs.getResultSet()) {
            ResultSetMetaData metaData = rs.getMetaData();
            while (rs.next()) {
                checkInterrupt();
                Object[] record = new Object[sourceFields.size()];
                long bytes = 0;
                for (int i = 1; i <= sourceFields.size(); ++i) {
                    try {
                        Object value = rs.getObject(i);
                        if (null == value) {
                            record[i - 1] = null;
                        } else {
                            int columnType = metaData.getColumnType(i);
                            bytes += JdbcTypesUtils.getObjectSize(columnType, value);
                            if (SqlDataUtils.isClob(value.getClass())) {
                                value = SqlDataUtils.extractString((Clob) value);
                            }
                            record[i - 1] = value;
                        }
                    } catch (Exception e) {
                        log.warn("!!! Read data from table [ {} ] use function ResultSet.getObject() error",
                                tableNameMapString, e);
                        record[i - 1] = null;
                    }
                }

                cache.add(transformer.doTransform(sourceSchemaName, sourceTableName, sourceFields, record));
                cacheBytes += bytes;
                totalCount.incrementAndGet();

                if (cache.size() >= BATCH_SIZE || cacheBytes >= MAX_CACHE_BYTES_SIZE) {
                    final long finalCacheBytes = cacheBytes;
                    this.memChannel.add(
                            BatchElement.builder()
                                    .tableNameMapString(tableNameMapString)
                                    .handler((arg1, arg2, logger) -> {
                                        long ret = tableWriter.executeInsert(arg1, arg2);
                                        logger.info("[FullCoverSync] handle write table [{}] batch record count: {}, the bytes size: {}",
                                                tableNameMapString, ret, DataSizeUtils.format(finalCacheBytes));
                                        return ret;
                                    })
                                    .columns(Lists.newArrayList(targetFields))
                                    .records(Lists.newArrayList(cache))
                                    .build()
                    );
                    cache.clear();
                    totalBytes.addAndGet(cacheBytes);
                    cacheBytes = 0;
                }
            }

            if (cache.size() > 0) {
                final long finalCacheBytes = cacheBytes;
                this.memChannel.add(
                        BatchElement.builder()
                                .tableNameMapString(tableNameMapString)
                                .handler((arg1, arg2, logger) -> {
                                    long ret = tableWriter.executeInsert(arg1, arg2);
                                    logger.info("[FullCoverSync] handle write table [{}] batch record count: {}, the bytes size: {}",
                                            tableNameMapString, ret, DataSizeUtils.format(finalCacheBytes));
                                    return ret;
                                })
                                .columns(Lists.newArrayList(targetFields))
                                .records(Lists.newArrayList(cache))
                                .build()
                );
                cache.clear();
                totalBytes.addAndGet(cacheBytes);
            }

            log.info("[FullCoverSync] handle read table [{}] total record count: {}, total bytes = {}",
                    tableNameMapString, totalCount.get(), DataSizeUtils.format(totalBytes.get()));
        } catch (Throwable e) {
            log.warn("[FullCoverSync] handle read table [{}] error: {}", e.getMessage());
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        } finally {
            // 如果正在读取大表数据的话，这里的close()会很慢
            srs.close();
        }

        return ReaderTaskResult
                .builder()
                .tableNameMapString(tableNameMapString)
                .successCount(1)
                .failureCount(0)
                .totalBytes(totalBytes.get())
                .recordCount(totalCount.get())
                .build();
    }

    /**
     * 变化量同步
     *
     * @param synchronizer
     * @param transformer
     * @return ReaderTaskResult
     */
    private ReaderTaskResult doIncreaseSynchronize(TableDataWriteProvider synchronizer,
                                                   RecordTransformProvider transformer) {
        final int BATCH_SIZE = fetchSize;

        List<String> sourceFields = new ArrayList<>();
        List<String> targetFields = new ArrayList<>();
        Map<String, String> columnNameMaps = new HashMap<>();
        for (int i = 0; i < targetColumnDescriptions.size(); ++i) {
            ColumnDescription scd = sourceColumnDescriptions.get(i);
            ColumnDescription tcd = targetColumnDescriptions.get(i);
            if (!StringUtils.isEmpty(tcd.getColumnName())) {
                sourceFields.add(scd.getColumnName());
                targetFields.add(tcd.getColumnName());
                columnNameMaps.put(scd.getColumnName(), tcd.getColumnName());
            }
        }

        TaskParamEntity.TaskParamEntityBuilder taskBuilder = TaskParamEntity.builder();
        taskBuilder.sourceFactoryProvider(sourceFactoryProvider);
        taskBuilder.targetFactoryProvider(targetFactoryProvider);
        taskBuilder.sourceSchemaName(sourceSchemaName);
        taskBuilder.sourceTableName(sourceTableName);
        taskBuilder.targetTableName(targetTableName);
        taskBuilder.targetSchemaName(targetSchemaName);
        taskBuilder.readSourceColumns(sourceFields);
        taskBuilder.writeTargetColumns(targetFields);
        taskBuilder.columnsMap(columnNameMaps);
        taskBuilder.deleteNotExistRow(targetProperties.getDeleteNotExistRow());
        taskBuilder.transformer(transformer);

        TaskParamEntity param = taskBuilder.build();

        synchronizer.prepare(targetSchemaName, targetTableName, targetFields, targetPrimaryKeys);

        RecordRowChangeCalculator calculator = new DefaultChangeCalculatorService();
        calculator.setFetchSize(fetchSize);
        calculator.setInterruptCheck(this::checkInterrupt);
        calculator.setRecordIdentical(false);
        calculator.setCheckJdbcType(false);

        // 执行实际的变化同步过程
        log.info("[IncreaseSync] Handle table by compare [{}] data now ... ", tableNameMapString);
        calculator.executeCalculate(param, new RecordRowHandler() {

            private long countInsert = 0;
            private long countUpdate = 0;
            private long countDelete = 0;
            private long countTotal = 0;
            private long cacheBytes = 0;

            private long countIgnored = 0;
            private final List<Object[]> cacheInsert = new LinkedList<>();
            private final List<Object[]> cacheUpdate = new LinkedList<>();
            private final List<Object[]> cacheDelete = new LinkedList<>();

            @Override
            public void handle(List<String> fields, Object[] record, int[] jdbcTypes, RowChangeTypeEnum flag) {
                switch (flag) {
                    case VALUE_INSERT:
                        if (!targetProperties.getTargetSyncOption().callInsert()) {
                            return;
                        }
                        cacheInsert.add(record);
                        countInsert++;
                        break;
                    case VALUE_CHANGED:
                        if (!targetProperties.getTargetSyncOption().callUpdate()) {
                            return;
                        }
                        cacheUpdate.add(record);
                        countUpdate++;
                        break;
                    case VALUE_DELETED:
                        if (!targetProperties.getTargetSyncOption().callDelete()) {
                            return;
                        } else {
                            cacheDelete.add(record);
                            countDelete++;
                        }
                        break;
                    case VALUE_IGNORED:
                        countIgnored++;
                        break;
                    default:
                        return;
                }

                long bytes = JdbcTypesUtils.getRecordSize(record, jdbcTypes);
                cacheBytes += bytes;
                totalBytes.addAndGet(bytes);
                totalCount.addAndGet(1);
                countTotal++;
                checkFull(fields);
            }

            /**
             * 检测缓存是否已满，如果已满执行同步操作
             *
             * @param fields 同步的字段列表
             */
            private void checkFull(List<String> fields) {
                checkInterrupt();
                if (cacheInsert.size() >= BATCH_SIZE || cacheUpdate.size() >= BATCH_SIZE
                        || cacheDelete.size() >= BATCH_SIZE || cacheBytes >= MAX_CACHE_BYTES_SIZE) {
                    if (cacheDelete.size() > 0) {
                        doDelete(fields);
                    }

                    if (cacheInsert.size() > 0) {
                        doInsert(fields);
                    }

                    if (cacheUpdate.size() > 0) {
                        doUpdate(fields);
                    }

                    cacheBytes = 0;
                }
            }

            @Override
            public void destroy(List<String> fields) {
                if (cacheDelete.size() > 0) {
                    doDelete(fields);
                }

                if (cacheInsert.size() > 0) {
                    doInsert(fields);
                }

                if (cacheUpdate.size() > 0) {
                    doUpdate(fields);
                }

                log.info("[IncreaseSync] Handle table by compare [{}] total count: {}, Insert:{},Update:{},Delete:{},Ignored:{} ",
                        tableNameMapString, countTotal, countInsert, countUpdate, countDelete, countIgnored);
            }

            private void doInsert(List<String> fields) {
                ReaderTaskThread.this.memChannel.add(
                        BatchElement.builder()
                                .tableNameMapString(tableNameMapString)
                                .handler((arg1, arg2, logger) -> {
                                    long ret = synchronizer.executeInsert(arg1, arg2);
                                    logger.info("[IncreaseSync] Handle write table [{}] record Insert count: {}",
                                            tableNameMapString, ret);
                                    return ret;
                                })
                                .columns(fields)
                                .records(Lists.newArrayList(cacheInsert))
                                .build()
                );
                cacheInsert.clear();
            }

            private void doUpdate(List<String> fields) {
                ReaderTaskThread.this.memChannel.add(
                        BatchElement.builder()
                                .tableNameMapString(tableNameMapString)
                                .handler((arg1, arg2, logger) -> {
                                    long ret = synchronizer.executeUpdate(arg1, arg2);
                                    logger.info("[IncreaseSync] Handle write table [{}] record Update count: {}",
                                            tableNameMapString, ret);
                                    return ret;
                                })
                                .columns(fields)
                                .records(Lists.newArrayList(cacheUpdate))
                                .build()
                );
                cacheUpdate.clear();
            }

            private void doDelete(List<String> fields) {
                ReaderTaskThread.this.memChannel.add(
                        BatchElement.builder()
                                .tableNameMapString(tableNameMapString)
                                .handler((arg1, arg2, logger) -> {
                                    long ret = synchronizer.executeDelete(arg2);
                                    logger.info("[IncreaseSync] Handle write table [{}] record Delete count: {}",
                                            tableNameMapString, ret);
                                    return ret;
                                })
                                .columns(fields)
                                .records(Lists.newArrayList(cacheDelete))
                                .build()
                );
                cacheDelete.clear();
            }

        });

        return ReaderTaskResult
                .builder()
                .tableNameMapString(tableNameMapString)
                .successCount(1)
                .failureCount(0)
                .recordCount(totalCount.get())
                .totalBytes(totalBytes.get())
                .build();
    }

    public SourceProperties getTblProperties() {
        List<String> columnNames = sourceColumnDescriptions.stream()
                .map(ColumnDescription::getColumnName)
                .collect(Collectors.toList());
        SourceProperties param = new SourceProperties();
        param.setProductType(sourceProductType);

        Properties dbProperties = new Properties();
        dbProperties.putAll(sourceProperties.getJdbcProperties());
        param.setDbProperties(dbProperties);
        param.setDriverClass(sourceProperties.getDriverClassName());
        param.setJdbcUrl(sourceProperties.getUrl());
        param.setUsername(sourceProperties.getUsername());
        param.setPassword(sourceProperties.getPassword());
        param.setSchemaName(sourceSchemaName);
        param.setTableName(sourceTableName);
        param.setColumnNames(columnNames);
        return param;
    }

    public SourceProperties getTargetTblProperties() {
        List<String> columnNames = targetColumnDescriptions.stream()
                .map(ColumnDescription::getColumnName)
                .collect(Collectors.toList());
        SourceProperties param = new SourceProperties();
        param.setProductType(targetProductType);

        Properties dbProperties = new Properties();
        dbProperties.putAll(targetProperties.getJdbcProperties());
        param.setDbProperties(dbProperties);
        param.setDriverClass(targetProperties.getDriverClassName());
        param.setJdbcUrl(targetProperties.getUrl());
        param.setUsername(targetProperties.getUsername());
        param.setPassword(targetProperties.getPassword());
        param.setSchemaName(targetSchemaName);
        param.setTableName(targetTableName);
        param.setColumnNames(columnNames);
        return param;
    }

    @Override
    protected ReaderTaskResult exceptProcess(Throwable t) {
        log.error("Error migration for table: {}.{}, error message: {}", tableDescription.getSchema(), tableDescription.getTableName(), t.getMessage(), t);
        log.error("", t);
        return ReaderTaskResult.builder()
                .tableNameMapString(tableNameMapString)
                .successCount(0)
                .failureCount(1)
                .recordCount(totalCount.get())
                .totalBytes(totalBytes.get())
                .throwable(t)
                .build();
    }

    @Override
    protected void afterProcess() {
    }

    /**
     * 检查MySQL数据库表的存储引擎是否为Innodb
     *
     * @param schemaName schema名
     * @param tableName  table名
     * @param dataSource 数据源
     * @return 为Innodb存储引擎时返回True, 否在为false
     */
    public static boolean isMysqlInnodbStorageEngine(String schemaName, String tableName,
                                                     DataSource dataSource) {
        String sql = "SELECT count(*) as total FROM information_schema.tables "
                + "WHERE table_schema=? AND table_name=? AND ENGINE='InnoDB'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }

            return false;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }
}
