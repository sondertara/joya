package com.sondertara.joya.core.kernel.support;


import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * @author huangxiaohu
 */
public class LoggingOutputStream extends OutputStream {

    private final ByteArrayOutputStream bao = new ByteArrayOutputStream(1024);

    private final Logger logger;
    private final Level level;

    public LoggingOutputStream(Logger logger,Level level) {
        this.logger = logger;
        this.level=level;
    }


    @Override
    public void write(int b) throws IOException {
        if (b == '\n') {
            String string = bao.toString(StandardCharsets.UTF_8.name());
            bao.reset();
            switch (level) {

                case INFO:
                    logger.info(string);
                    break;
                case WARN:
                    logger.warn(string);
                    break;
                case DEBUG:
                    logger.debug(string);
                    break;
                case ERROR:
                    logger.error(string);
                    break;
                case TRACE:
                    logger.trace(string);
                    break;
                default:

            }
        } else {
            bao.write(b);
        }

    }
}
