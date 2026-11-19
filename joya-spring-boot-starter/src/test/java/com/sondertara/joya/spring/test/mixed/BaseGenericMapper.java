package com.sondertara.joya.spring.test.mixed;

import com.sondertara.joya.core.mapper.JoyaMapper;

/**
 * 供扫描测试验证：带未具化类型参数的中间父接口必须被扫描器自动跳过。
 */
public interface BaseGenericMapper<T> extends JoyaMapper<T> {
}
