
package com.sondertara.joya.migration.provider;


import com.sondertara.common.base.Named;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialect;
import com.sondertara.joya.migration.ProductExtensionFactory;
import com.sondertara.joya.migration.features.ProductFeatures;
import com.sondertara.joya.migration.provider.manage.TableManageProvider;
import com.sondertara.joya.migration.provider.meta.MetadataProvider;
import com.sondertara.joya.migration.provider.query.TableDataQueryProvider;
import com.sondertara.joya.migration.provider.write.TableDataWriteProvider;
import com.sondertara.joya.migration.provider.transform.RecordTransformProvider;

import javax.sql.DataSource;

public interface ProductFactoryProvider extends Named {

    /**
     * 获取数据库类型
     *
     * @return ProductTypeEnum
     */
    DbType getProductType();

    SqlDialect getSqlDialect();

    /**
     * 获取数据源
     *
     * @return DataSource
     */
    DataSource getDataSource();

    /**
     * 获取数据库特征
     *
     * @return ProductFeatures
     */
    ProductFeatures getProductFeatures();

    /**
     * 获取元数据查询Provider
     *
     * @return MetadataQueryProvider
     */
    MetadataProvider createMetadataQueryProvider();

    /**
     * 获取表数据查询Provider
     *
     * @return TableDataQueryProvider
     */
    default TableDataQueryProvider createTableDataQueryProvider() {
        return ReflectUtils.newInstance(ProductExtensionFactory.findExtensionClass(TableDataQueryProvider.class,null),this);
    }

    /**
     * 获取记录转换Provider
     *
     * @return RecordTransformProvider
     */
    default RecordTransformProvider createRecordTransformProvider() {
        return ReflectUtils.newInstance(ProductExtensionFactory.findExtensionClass(RecordTransformProvider.class,null),this);
    }

    /**
     * 获取表批量写入Provider
     *
     * @param useInsert 是否使用insert写入(只对PG有效)
     * @return TableWriteProvider
     */
    default TableDataWriteProvider createTableDataWriteProvider(boolean useInsert) {
        return ReflectUtils.newInstance(ProductExtensionFactory.findExtensionClass(TableDataWriteProvider.class,null),this);

    }

    /**
     * 获取表操作Provider
     *
     * @return TableManageProvider
     */
    default TableManageProvider createTableManageProvider() {
        return ReflectUtils.newInstance(ProductExtensionFactory.findExtensionClass(TableManageProvider.class,null),this);

    }


    @Override
    default String getName() {
        return StringUtils.subBefore(getClass().getSimpleName(), "ProductFactoryProvider", false);
    }
}
