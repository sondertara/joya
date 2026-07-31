package com.sondertara.joya.core.model;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * entity with time
 *
 * @author huangxiaohu
 */
public class BaseEntity implements Serializable {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;
}
