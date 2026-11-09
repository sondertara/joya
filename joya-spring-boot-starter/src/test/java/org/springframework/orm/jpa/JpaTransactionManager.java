package org.springframework.orm.jpa;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试桩：用于在无 {@code spring-orm} 依赖的离线单测中验证 {@code JpaTransactionManagerSupport}
 * 对已绑定与未绑定 {@link DataSource} 的 {@code JpaTransactionManager} 的识别与事务连接共享。
 */
public class JpaTransactionManager implements ResourceTransactionManager {

    private final Object entityManagerFactory = new Object();
    private DataSource dataSource;
    private DataSourceTransactionManager delegate;
    private final AtomicInteger getTransactionCalls = new AtomicInteger();

    public JpaTransactionManager() {
    }

    public JpaTransactionManager(DataSource dataSource) {
        setDataSource(dataSource);
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        this.delegate = dataSource == null ? null : new DataSourceTransactionManager(dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public int getTransactionCalls() {
        return getTransactionCalls.get();
    }

    @Override
    public Object getResourceFactory() {
        return entityManagerFactory;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        getTransactionCalls.incrementAndGet();
        return delegate != null ? delegate.getTransaction(definition) : new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        if (delegate != null) {
            delegate.commit(status);
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        if (delegate != null) {
            delegate.rollback(status);
        }
    }
}
