package com.sondertara.joya.enums;
/**
 * 请求返回通用结果
 *
 * @author huangxiaohu
 * @date 2021/7/21 9:56
 */

public enum ReqResultCodeEnum {
    /**
     * 参数为空
     */
    PARAM_EMPTY("PARAM_EMPTY"),
    /**
     * 参数错误
     */
    PARAM_ERROR("PARAM_ERROR"),
    /**
     * 没有数据
     */
    NO_DATA("NO_DATA"),
    /**
     * 请求连接超时
     */
    CONNECT_TIMEOUT("CONNECT_TIMEOUT"),
    CONNECT_REFUSED("CONNECT_REFUSED"),
    /**
     * 获取响应数据超时
     */
    READ_TIMEOUT("READ_TIMEOUT"),
    /**
     * Socket 连接超时
     */
    SOCKET_TIMEOUT("SOCKET_TIMEOUT"),
    /**
     * 请求资源异常
     */
    RESOURCE_REQUEST_ERROR("RESOURCE_REQUEST_ERROR"),
    /**
     * 请求异常
     */
    REQUEST_ERROR("REQUEST_ERROR"),
    /**
     * 系统错误
     */
    SYSTEM_ERROR("SYSTEM_ERROR"),
    ;
    private String code;

    ReqResultCodeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

}
