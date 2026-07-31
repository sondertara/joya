package com.sondertara.joya.migration.provider.write;


import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.utils.ObjectCastUtils;

import java.util.List;

public class AutoCastTableDataWriteProvider extends DefaultTableDataWriteProvider {


    public AutoCastTableDataWriteProvider(ProductFactoryProvider factoryProvider) {
        super(factoryProvider);
    }

    @Override
    public long executeInsert(List<String> fieldNames, List<Object[]> records) {


        int[] insertArgsType = getInsertArgsType(fieldNames);
        records.forEach((Object[] row) -> {
            for (int i = 0; i < row.length; ++i) {
                row[i] = ObjectCastUtils.castByJdbcType(insertArgsType[i], row[i]);
            }
        });
        return super.executeInsert(fieldNames, records);
    }

    @Override
    public long executeUpdate(List<String> fieldNames, List<Object[]> records) {
        records.forEach((Object[] row) -> {
            for (int i = 0; i < row.length; ++i) {
                row[i] = ObjectCastUtils.castByDetermine(row[i]);
            }
        });
        return super.executeUpdate(fieldNames, records);
    }


}
