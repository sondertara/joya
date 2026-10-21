package com.sondertara.joya.utils;

/**
 * 十六进制工具，替代 common-tara 的 HexUtils。
 *
 * @author huangxiaohu
 */
public final class HexUtils {

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    private HexUtils() {
    }

    public static String encodeHexStr(byte[] data) {
        if (data == null) {
            return null;
        }
        char[] chars = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            chars[i * 2] = HEX_CHARS[v >>> 4];
            chars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(chars);
    }

    public static byte[] decodeHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character at position " + i);
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
