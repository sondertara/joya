package com.sondertara.joya.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期时间工具，替代 common-tara 的 LocalDateTimeUtils。
 *
 * @author huangxiaohu
 */
public final class LocalDateTimeUtils {

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private LocalDateTimeUtils() {
    }

    public static Date parse(String dateStr) {
        return parse(dateStr, DEFAULT_DATE_PATTERN);
    }

    public static Date parse(String dateStr, String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDateTime ldt = LocalDateTime.parse(dateStr, formatter);
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                LocalDate ld = LocalDate.parse(dateStr, formatter);
                return Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (Exception ex) {
                throw new RuntimeException("Cannot parse date: " + dateStr, ex);
            }
        }
    }

    public static LocalDate parseLocalDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(DATE_PATTERN));
        } catch (Exception e) {
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)).toLocalDate();
        }
    }

    public static LocalDateTime parseLocalDateTime(String dateStr) {
        return LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(DATE_TIME_PATTERN));
    }

    public static LocalTime localTime(Date date) {
        Instant instant = date.toInstant();
        return instant.atZone(ZoneId.systemDefault()).toLocalTime();
    }

    public static String format(LocalDateTime ldt, String pattern) {
        return ldt.format(DateTimeFormatter.ofPattern(pattern));
    }

    public static String format(LocalDateTime ldt) {
        return format(ldt, DATE_TIME_PATTERN);
    }

    public static String format(Date date, String pattern) {
        return date.toInstant().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(pattern));
    }

    public static String format(Date date) {
        return format(date, DATE_TIME_PATTERN);
    }

    public static Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    public static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
