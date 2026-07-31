package com.sondertara.joya.core.jdbc.support.dialect;

import com.sondertara.joya.core.jdbc.support.meta.TableDescription;

import java.sql.SQLException;

public interface TableGenerator {
    String generate(TableDescription table) throws SQLException;
}
