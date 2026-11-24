package com.sondertara.joya.core.kernel.support;

import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 将流式字节按行缓冲并输出到 SLF4J {@link Logger}（支持块写入、CRLF 剥离与关闭时尾行刷盘）。
 */
public class LoggingOutputStream extends OutputStream {

    private final ByteArrayOutputStream bao = new ByteArrayOutputStream(256);
    private final Logger logger;
    private final Level level;

    public LoggingOutputStream(Logger logger, Level level) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.level = Objects.requireNonNull(level, "level must not be null");
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            emitLine();
        } else if (b != '\r') {
            bao.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        int segStart = off;
        int end = off + len;
        for (int i = off; i < end; i++) {
            byte ch = b[i];
            if (ch == '\n' || ch == '\r') {
                if (i > segStart) {
                    bao.write(b, segStart, i - segStart);
                }
                if (ch == '\n') {
                    emitLine();
                }
                segStart = i + 1;
            }
        }
        if (segStart < end) {
            bao.write(b, segStart, end - segStart);
        }
    }

    @Override
    public void flush() {
        if (bao.size() > 0) {
            emitLine();
        }
    }

    @Override
    public void close() {
        flush();
    }

    private void emitLine() {
        String message = bao.toString(StandardCharsets.UTF_8);
        bao.reset();
        switch (level) {
            case INFO -> logger.info(message);
            case WARN -> logger.warn(message);
            case DEBUG -> logger.debug(message);
            case ERROR -> logger.error(message);
            case TRACE -> logger.trace(message);
        }
    }
}
