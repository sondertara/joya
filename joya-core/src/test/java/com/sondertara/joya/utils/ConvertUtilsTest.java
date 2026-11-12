package com.sondertara.joya.utils;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E-2 回归：带偏移的时间值转成无偏移类型时必须按「同一时刻」换算；库内原始偏移可读回时必须保留。
 */
class ConvertUtilsTest {

    private static final OffsetDateTime UTC_INSTANT = OffsetDateTime.parse("2024-03-01T10:00:00Z");

    @Test
    void offsetInputsAreConvertedToTheSameInstantInJvmZone() {
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(UTC_INSTANT.atZoneSameInstant(zone).toLocalDateTime(),
                ConvertUtils.convert(UTC_INSTANT, LocalDateTime.class));
        assertEquals(UTC_INSTANT.atZoneSameInstant(zone).toLocalDate(),
                ConvertUtils.convert(UTC_INSTANT, LocalDate.class));
        assertEquals(UTC_INSTANT.atZoneSameInstant(zone).toLocalTime(),
                ConvertUtils.convert(UTC_INSTANT, LocalTime.class));
    }

    @Test
    void h2StoredOffsetIsPreservedWhenReadingOffsetDateTime() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:joya_convert_tz;DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE tz_probe (id INT PRIMARY KEY, ts TIMESTAMP WITH TIME ZONE)");
            st.execute("INSERT INTO tz_probe VALUES (1, TIMESTAMP WITH TIME ZONE '2024-03-01 10:00:00+09:00')");
            try (ResultSet rs = st.executeQuery("SELECT ts FROM tz_probe WHERE id = 1")) {
                rs.next();
                OffsetDateTime expected = OffsetDateTime.parse("2024-03-01T10:00:00+09:00");
                OffsetDateTime actual = (OffsetDateTime) SqlDataUtils.getResultSetValue(rs, 1, OffsetDateTime.class);
                assertEquals(expected, actual, "读取 OffsetDateTime 必须保留列内原始偏移");
                assertEquals(expected.toInstant(), actual.toInstant());
            }
        }
    }
}
