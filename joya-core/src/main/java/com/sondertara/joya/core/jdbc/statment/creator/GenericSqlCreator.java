package com.sondertara.joya.core.jdbc.statment.creator;

import com.sondertara.joya.core.jdbc.support.TableMetaContext;

import java.util.HashSet;
import java.util.Set;

/**
 * @author huangxiaohu
 */
public abstract class GenericSqlCreator extends AbstractSqlCreator {
    private Set<String> generatedKeyNames=new HashSet<>();


    private Set<String> declaredColumns=new HashSet<>();

    protected GenericSqlCreator(TableMetaContext tableMetaContext) {
        super(tableMetaContext);
    }


    public Set<String> getGeneratedKeyNames() {
        return generatedKeyNames;
    }

    public void setGeneratedKeyNames(Set<String> generatedKeyNames) {
        this.generatedKeyNames = generatedKeyNames;
    }

    public Set<String> getDeclaredColumns() {
        return declaredColumns;
    }



    public void setDeclaredColumns(Set<String> declaredColumns) {
        this.declaredColumns = declaredColumns;
    }


}
