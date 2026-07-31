/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package com.sondertara.joya.spring.jpa.hibernate.transformer;

import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import org.hibernate.transform.AliasedTupleSubsetResultTransformer;

import java.io.Reader;
import java.sql.Clob;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author SonderTara
 */
public class AliasToMapResultTransformer extends AliasedTupleSubsetResultTransformer {

    public static ConcurrentHashMap<String, AliasToMapResultTransformer> TRANSFORMER = new ConcurrentHashMap<String, AliasToMapResultTransformer>();

    private final Boolean camelCase;

    private AliasToMapResultTransformer(Boolean camelCase) {
        this.camelCase = camelCase;
    }

    public static AliasToMapResultTransformer getInstance(Boolean camelCase) {

        return TRANSFORMER.computeIfAbsent(camelCase.toString(), k -> new AliasToMapResultTransformer(camelCase));
    }

    @Override
    public Object transformTuple(Object[] tuple, String[] aliases) {
        Map<String, Object> result = new LinkedHashMap<>(tuple.length);
        for (int i = 0; i < tuple.length; i++) {
            String alias = aliases[i];
            if (null == alias) {
                continue;
            }
            Object value = tuple[i];
            if (camelCase) {
                int index = alias.indexOf(".");
                if (index > -1) {
                    alias = alias.substring(index + 1);
                }
                alias = StringUtils.toCamelCase(alias);
            }
            if (null != value) {
                if (SqlDataUtils.isClob(value.getClass())) {
                    value = SqlDataUtils.extractString((Clob) value);
                } else if (Reader.class.isAssignableFrom(value.getClass())) {
                    value = SqlDataUtils.extractString((Reader) value);
                } else if ("oracle.sql.TIMESTAMP".equals(value.getClass().getName())) {
                    value = ReflectUtils.invoke(value, "timestampValue");
                }
            }
            result.put(alias, value);
        }
        return result;
    }

    @Override
    public boolean isTransformedValueATupleElement(String[] aliases, int tupleLength) {
        return false;
    }
}
