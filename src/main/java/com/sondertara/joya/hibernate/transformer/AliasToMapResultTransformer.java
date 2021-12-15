/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package com.sondertara.joya.hibernate.transformer;

import com.sondertara.common.util.StringUtils;
import org.hibernate.transform.AliasedTupleSubsetResultTransformer;

import java.util.HashMap;
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object transformTuple(Object[] tuple, String[] aliases) {
        Map result = new HashMap(tuple.length);
        for (int i = 0; i < tuple.length; i++) {
            String alias = aliases[i];
            if (alias != null) {
                if (camelCase) {
		   int index = alias.indexOf(".");
		   if(index > -1){
		   alias= alias.substring(index+1);
		   }
                    alias = StringUtils.toCamelCase(alias);
                }
                result.put(alias, tuple[i]);
            }
        }
        return result;
    }

    @Override
    public boolean isTransformedValueATupleElement(String[] aliases, int tupleLength) {
        return false;
    }
}
