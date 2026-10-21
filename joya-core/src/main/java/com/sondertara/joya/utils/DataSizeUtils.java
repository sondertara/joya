package com.sondertara.joya.utils;

import java.text.DecimalFormat;

/**
 * 数据大小格式化工具，替代 common-tara 的 DataSizeUtils。
 *
 * @author huangxiaohu
 */
public final class DataSizeUtils {

    private static final DecimalFormat FORMAT = new DecimalFormat("#.##");
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private DataSizeUtils() {
    }

    public static String format(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double size = bytes;
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < UNITS.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return FORMAT.format(size) + " " + UNITS[unitIndex];
    }

    public static String format(int bytes) {
        return format((long) bytes);
    }
}
