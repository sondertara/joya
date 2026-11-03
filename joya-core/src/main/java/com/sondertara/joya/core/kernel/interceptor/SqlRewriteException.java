package com.sondertara.joya.core.kernel.interceptor;

/**
 * SQL 结构改写失败（例如在批量语句中尝试逐行绑定、或改写器返回非法节点）。
 */
public class SqlRewriteException extends RuntimeException {

    public SqlRewriteException(String message) {
        super(message);
    }

    public SqlRewriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
