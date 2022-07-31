package com.sondertara.joya.utils;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sondertara.common.model.ResultDTO;
import com.sondertara.common.util.CollectionUtils;
import com.sondertara.joya.enums.ReqResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * 自定义RestTemplate工具
 *
 * @author huangxiaohu
 * @date 2021-06-20
 */

@Slf4j
public class RestTemplateUtils {
    /**
     * 使用fastjson解析
     */
    private static volatile RestTemplate restTemplate;

    /**
     * 连接超时,单位秒
     */
    private static final int CONNECT_TIMEOUT = 10;
    /**
     * 获取响应超时,单位秒
     */
    private static final int READ_TIMEOUT = 60;

    private static final String DISABLE_PRINT_CACHE_KEY = "RESTFUL_PRINT_RSP_CACHE";

    public static RestTemplate getInstance() {
        if (null == restTemplate) {
            synchronized (RestTemplate.class) {
                if (null == restTemplate) {
//                    FastJsonHttpMessageConverter fastJsonHttpMessageConverter = new FastJsonHttpMessageConverter();
//                    List<MediaType> supportedMediaTypes = new ArrayList<>();
//                    supportedMediaTypes.add(MediaType.APPLICATION_JSON);
//                    supportedMediaTypes.add(MediaType.APPLICATION_ATOM_XML);
//                    supportedMediaTypes.add(MediaType.APPLICATION_FORM_URLENCODED);
//                    supportedMediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
//                    supportedMediaTypes.add(MediaType.APPLICATION_PDF);
//                    supportedMediaTypes.add(MediaType.APPLICATION_RSS_XML);
//                    supportedMediaTypes.add(MediaType.APPLICATION_XHTML_XML);
//                    supportedMediaTypes.add(MediaType.APPLICATION_XML);
//                    supportedMediaTypes.add(MediaType.IMAGE_GIF);
//                    supportedMediaTypes.add(MediaType.IMAGE_JPEG);
//                    supportedMediaTypes.add(MediaType.IMAGE_PNG);
//                    supportedMediaTypes.add(MediaType.TEXT_EVENT_STREAM);
//                    supportedMediaTypes.add(MediaType.TEXT_HTML);
//                    supportedMediaTypes.add(MediaType.TEXT_MARKDOWN);
//                    supportedMediaTypes.add(MediaType.TEXT_PLAIN);
//                    supportedMediaTypes.add(MediaType.TEXT_XML);
//
//                    fastJsonHttpMessageConverter.setSupportedMediaTypes(supportedMediaTypes);
//                    fastJsonHttpMessageConverter.setDefaultCharset(StandardCharsets.UTF_8);
//                    FastJsonConfig fastJsonConfig = new FastJsonConfig();
//                    fastJsonConfig.setSerializerFeatures(
//                            SerializerFeature.WriteMapNullValue,
//                            SerializerFeature.WriteNullStringAsEmpty,
//                            SerializerFeature.WriteNullListAsEmpty,
//                            SerializerFeature.DisableCircularReferenceDetect);
//                    fastJsonHttpMessageConverter.setFastJsonConfig(fastJsonConfig);
                    restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT)).setReadTimeout(Duration.ofSeconds(READ_TIMEOUT)).build();

                    List<HttpMessageConverter<?>> messageConverters = restTemplate.getMessageConverters();

                    for (int i = 0; i < messageConverters.size(); i++) {
                        HttpMessageConverter<?> converter = messageConverters.get(i);
                        if (converter instanceof StringHttpMessageConverter) {
                            converter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
                            messageConverters.set(i, converter);
                            break;
                        }
                        //} else if (converter instanceof MappingJackson2HttpMessageConverter) {
                        //    List<MediaType> mediaTypes = new ArrayList<>(converter.getSupportedMediaTypes());
                        //    mediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
                        //    MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter = (MappingJackson2HttpMessageConverter) converter;
                        //
                        //    mappingJackson2HttpMessageConverter.setSupportedMediaTypes(mediaTypes);
                        //    messageConverters.set(i, mappingJackson2HttpMessageConverter);
                        //}

//                        } else if (converter instanceof GsonHttpMessageConverter || converter instanceof MappingJackson2HttpMessageConverter) {
//                            converter = fastJsonHttpMessageConverter;
//                            messageConverters.set(i, converter);
//                        }

                    }

                }
            }
        }
        return restTemplate;
    }

    private RestTemplateUtils() {

    }


    /**
     * Get 请求，参数放在url中
     *
     * @param url          url
     * @param params       参数
     * @param responseType 接收响应的类
     * @return 响应结果
     */
    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, Class<T> responseType) {
        return get(url, params, responseType, null);
    }

    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType) {
        return get(url, params, responseType, null);
    }

    /**
     * post
     *
     * @param url          url
     * @param params       参数
     * @param responseType 接收响应的类
     * @return 响应结果
     */
    public static <T> ResultDTO<T> post(String url, Map<String, Object> params, Class<T> responseType) {
        return post(url, params, responseType, null);
    }

    public static <T> ResultDTO<T> post(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType) {
        return post(url, params, responseType, null);
    }

    /**
     * Get 请求，参数放在url中
     *
     * @param url          url
     * @param params       参数
     * @param headers      请求头
     * @param responseType 接收响应的类
     * @return 响应结果
     */
    @SuppressWarnings("unchecked")
    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) {
        log.info("RESTful get请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> responseEntity = RestTemplateUtils.getInstance().exchange(url, HttpMethod.GET, httpEntity, responseType);
            T result = responseEntity.getBody();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful get请求结束 url=[{}],cost time=[{}],params=[{}]", url, System.currentTimeMillis() - start, params);
            } else {
                log.info("RESTful get请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, System.currentTimeMillis() - start, params, responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful get请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }


    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("RESTful get请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> responseEntity = RestTemplateUtils.getInstance().exchange(url, HttpMethod.GET, httpEntity, responseType);

            T result = responseEntity.getBody();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful get请求结束 url=[{}],cost time=[{}],params=[{}]", url, System.currentTimeMillis() - start, params);
            } else {
                log.info("RESTful get请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, System.currentTimeMillis() - start, params, responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful get请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }

    /**
     * POST 请求 参数放在body中 兼容form和json
     *
     * @param url          url
     * @param params       请求参数
     * @param headers      请求头
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> post(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) {
        log.info("RESTful post请求开始 url=[{}],params=[{}],responseType=[{}]", url, JSON.toJSONString(params), responseType);
        long start = System.currentTimeMillis();
        try {
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));

            T result = RestTemplateUtils.getInstance().postForObject(url, httpEntity, responseType);
            long end = System.currentTimeMillis();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}]", url, end - start, JSON.toJSONString(params));
            } else {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, end - start, JSON.toJSONString(params), responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }

    /**
     * POST 请求 json参数放在body中
     *
     * @param url          url
     * @param json         请求json参数
     * @param headers      请求头
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> post(String url, String json, Class<T> responseType, Map<String, String> headers) {
        log.info("RESTful post请求开始 url=[{}],params=[{}],responseType=[{}]", url, json, responseType);
        long start = System.currentTimeMillis();
        try {
            if (null == headers) {
                headers = new HashMap<>(1);
            }
            headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> httpEntity = buildHttpEntity(json, buildHeaders(headers));

            T result = RestTemplateUtils.getInstance().postForObject(url, httpEntity, responseType);
            long end = System.currentTimeMillis();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}]", url, end - start, json);
            } else {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, end - start, json, responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, json, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }

    /**
     * post json
     *
     * @param url          url
     * @param json         json str
     * @param responseType rsp
     * @param <T>          the type of result
     * @return result
     */
    public static <T> ResultDTO<T> post(String url, String json, ParameterizedTypeReference<T> responseType) {
        return post(url, json, responseType, null);
    }

    /**
     * POST 请求 json参数放在body中
     *
     * @param url          url
     * @param json         请求json参数
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> post(String url, String json, Class<T> responseType) {
        return post(url, json, responseType, null);
    }

    /**
     * post json
     *
     * @param url          url
     * @param json         json str
     * @param responseType rsp
     * @param headers      headers
     * @param <T>          the type of result
     * @return result
     */
    public static <T> ResultDTO<T> post(String url, String json, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("RESTful post请求开始 url=[{}],params=[{}],responseType=[{}]", url, json, responseType);
        long start = System.currentTimeMillis();
        try {
            if (null == headers) {
                headers = new HashMap<>(1);
            }
            headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> httpEntity = buildHttpEntity(json, buildHeaders(headers));
            ResponseEntity<T> response = RestTemplateUtils.getInstance().exchange(url, HttpMethod.POST, httpEntity, responseType);
            T result = response.getBody();
            long end = System.currentTimeMillis();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}]", url, end - start, json);
            } else {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, end - start, json, responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, json, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }


    public static <T> ResultDTO<T> post(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("RESTful post请求开始 url=[{}],params=[{}],responseType=[{}]", url, JSON.toJSONString(params), responseType);
        long start = System.currentTimeMillis();
        try {
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));

            ResponseEntity<T> response = RestTemplateUtils.getInstance().exchange(url, HttpMethod.POST, httpEntity, responseType);
            T result = response.getBody();
            long end = System.currentTimeMillis();
            if (Boolean.TRUE.equals(ThreadLocalUtil.get(DISABLE_PRINT_CACHE_KEY))) {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}]", url, end - start, JSON.toJSONString(params));
            } else {
                log.info("RESTful post请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, end - start, JSON.toJSONString(params), responseType, result);
            }
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } finally {
            ThreadLocalUtil.clear();
        }
    }

    /**
     * PUT 请求
     *
     * @param url          url
     * @param params       请求参数
     * @param headers      请求头
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> put(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) {
        log.info("RESTful put请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.PUT, httpEntity, responseType);
            T result = exchange.getBody();
            long end = System.currentTimeMillis();
            log.info("RESTful put请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],exchange=[{}]", url, end - start, params, responseType, exchange);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful put请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    public static <T> ResultDTO<T> put(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("RESTful put请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();

        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.PUT, httpEntity, responseType);
            T result = exchange.getBody();
            log.info("RESTful put请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],exchange=[{}]", url, System.currentTimeMillis() - start, params, responseType, exchange);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful put请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    /**
     * DELETE 请求 参数放在url中
     *
     * @param url          url
     * @param params       请求参数
     * @param headers      请求头
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> delete(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) {
        log.info("RESTful delete请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.DELETE, httpEntity, responseType, params);
            T result = exchange.getBody();
            log.debug("RESTful delete请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],exchange=[{}]", url, System.currentTimeMillis() - start, params, responseType, exchange);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful delete请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    public static <T> ResultDTO<T> delete(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("RESTful delete请求开始 url=[{}],params=[{}],responseType=[{}]", url, params, responseType);
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.DELETE, httpEntity, responseType, params);
            T result = exchange.getBody();
            log.info("RESTful delete请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],exchange=[{}]", url, System.currentTimeMillis() - start, params, responseType, exchange);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful delete请求失败 url=[{}],params=[{}],responseType=[{}],errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    /**
     * post xml请求
     *
     * @param url          请求地址
     * @param data         请求数据
     * @param responseType 响应映射类
     * @return 请求结果
     */
    public static <T> ResultDTO<T> postXml(String url, String data, Class<T> responseType) {
        log.info("RESTful post xml 请求开始 url=[{}],data=[{}],responseType=[{}]", url, data, responseType);
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            HttpEntity<String> httpEntity = new HttpEntity<>(data, headers);
            T result = RestTemplateUtils.getInstance().postForObject(url, httpEntity, responseType);
            log.info("RESTful postXml请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, System.currentTimeMillis() - start, data, responseType, result);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post xml 请求失败 url=[{}],data=[{}],responseType=[{}],errMsg:{}", url, data, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    public static <T> ResultDTO<T> postXml(String url, String data, ParameterizedTypeReference<T> responseType) {
        log.info("RESTful post xml 请求开始 url=[{}],data=[{}],responseType=[{}]", url, data, responseType);
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            HttpEntity<String> httpEntity = new HttpEntity<>(data, headers);
            T result = RestTemplateUtils.getInstance().exchange(url, HttpMethod.POST, httpEntity, responseType).getBody();
            log.info("RESTful postXml请求结束 url=[{}],cost time=[{}],params=[{}],responseType=[{}],result=[{}]", url, System.currentTimeMillis() - start, data, responseType, result);
            return ResultDTO.success(result);
        } catch (Exception e) {
            log.error("RESTful post xml 请求失败 url=[{}],data=[{}],responseType=[{}],errMsg:{}", url, data, responseType, e.getMessage());
            return handleFailResult(e);
        }
    }

    public static String buildUrl(String host, String path) {
        StringBuilder urlBuilder = new StringBuilder(host);

        if (host.endsWith("/") && path.startsWith("/")) {
            urlBuilder.append(path.substring(1));
        }
        if (null != path) {

            urlBuilder.append(path);
        }
        return urlBuilder.toString();
    }

    private static <T> HttpEntity<T> buildHttpEntity(T params, HttpHeaders headers) {
        return new HttpEntity<>(params, headers);
    }


    public static HttpHeaders buildHeaders(Map<String, String> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return null;
        }
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        return httpHeaders;
    }

    public static String buildUrl(String url, Map<String, Object> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        //如果存在參數 放在url中
        if (null != params && !params.isEmpty()) {
            params.forEach(builder::queryParam);
        }
        return builder.build().toString();
    }

    private static <T> ResultDTO<T> handleFailResult(Exception ex) {
        ResultDTO<T> result;
        if (ex instanceof ResourceAccessException) {
            Throwable cause = ex.getCause();
            if (cause instanceof ConnectException) {
                result = ResultDTO.fail(ReqResultCode.CONNECT_REFUSED.getCode(), ex.getMessage());
            } else if (cause instanceof SocketTimeoutException) {
                if (cause.getMessage().toLowerCase().contains("connect")) {
                    result = ResultDTO.fail(ReqResultCode.CONNECT_TIMEOUT.getCode(), ex.getMessage());
                } else if (cause.getMessage().toLowerCase().contains("read")) {
                    result = ResultDTO.fail(ReqResultCode.READ_TIMEOUT.getCode(), ex.getMessage());
                } else {
                    result = ResultDTO.fail(ReqResultCode.SOCKET_TIMEOUT.getCode(), ex.getMessage());
                }
            } else {
                result = ResultDTO.fail(ReqResultCode.RESOURCE_REQUEST_ERROR.getCode(), ex.getMessage());
            }
        } else if (ex instanceof HttpStatusCodeException) {
            HttpStatusCodeException e = (HttpStatusCodeException) ex;
            JSONObject object = new JSONObject();
            object.put("rspBody", e.getResponseBodyAsString());
            object.put("errMsg", e.getMessage());
            result = ResultDTO.fail(String.valueOf(e.getRawStatusCode()), object.toJSONString());
        } else if (ex instanceof UnknownContentTypeException) {
            UnknownContentTypeException e = (UnknownContentTypeException) ex;
            JSONObject object = new JSONObject();
            object.put("rspBody", e.getResponseBodyAsString());
            object.put("errMsg", e.getMessage());
            object.put("Content-type", e.getContentType().toString());
            object.put("headers", Objects.requireNonNull(e.getResponseHeaders()).toSingleValueMap());
            result = ResultDTO.fail(String.valueOf(e.getRawStatusCode()), object.toJSONString());
        } else {
            result = ResultDTO.fail(ReqResultCode.REQUEST_ERROR.getCode(), ex.getMessage());
        }
        log.error("请求接口失败,失败原因:\n{}", result);
        return result;
    }

    public static void withNoPrintRsp() {
        ThreadLocalUtil.put(DISABLE_PRINT_CACHE_KEY, true);
    }
}

