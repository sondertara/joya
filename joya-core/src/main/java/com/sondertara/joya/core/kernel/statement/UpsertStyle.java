package com.sondertara.joya.core.kernel.statement;

/**
 * 方言的原生 upsert 语法族。
 */
public enum UpsertStyle {
    /** 不支持原生 upsert，回退 select-then-write。 */
    NONE,
    /** PostgreSQL / SQLite: {@code ON CONFLICT (...) DO UPDATE SET}. */
    ON_CONFLICT,
    /** MySQL / MariaDB: {@code ON DUPLICATE KEY UPDATE}. */
    ON_DUPLICATE_KEY,
    /** H2: {@code MERGE INTO ... KEY (...) VALUES (...)}. */
    MERGE
}
