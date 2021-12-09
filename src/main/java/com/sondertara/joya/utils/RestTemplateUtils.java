package com.sondertara.joya.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sondertara.common.model.ResultDTO;
import com.sondertara.common.util.CollectionUtils;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    private RestTemplateUtils() {

    }

    public static RestTemplate getInstance() {
        if (null == restTemplate) {
            synchronized (RestTemplate.class) {
                if (null == restTemplate) {
                    // FastJsonHttpMessageConverter fastJsonHttpMessageConverter = new
                    // FastJsonHttpMessageConverter();
                    // List<MediaType> supportedMediaTypes = new ArrayList<>();
                    // supportedMediaTypes.add(MediaType.APPLICATION_JSON);
                    // supportedMediaTypes.add(MediaType.APPLICATION_ATOM_XML);
                    // supportedMediaTypes.add(MediaType.APPLICATION_FORM_URLENCODED);
                    // supportedMediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
                    // supportedMediaTypes.add(MediaType.APPLICATION_PDF);
                    // supportedMediaTypes.add(MediaType.APPLICATION_RSS_XML);
                    // supportedMediaTypes.add(MediaType.APPLICATION_XHTML_XML);
                    // supportedMediaTypes.add(MediaType.APPLICATION_XML);
                    // supportedMediaTypes.add(MediaType.IMAGE_GIF);
                    // supportedMediaTypes.add(MediaType.IMAGE_JPEG);
                    // supportedMediaTypes.add(MediaType.IMAGE_PNG);
                    // supportedMediaTypes.add(MediaType.TEXT_EVENT_STREAM);
                    // supportedMediaTypes.add(MediaType.TEXT_HTML);
                    // supportedMediaTypes.add(MediaType.TEXT_MARKDOWN);
                    // supportedMediaTypes.add(MediaType.TEXT_PLAIN);
                    // supportedMediaTypes.add(MediaType.TEXT_XML);
                    //
                    // fastJsonHttpMessageConverter.setSupportedMediaTypes(supportedMediaTypes);
                    // fastJsonHttpMessageConverter.setDefaultCharset(StandardCharsets.UTF_8);
                    // FastJsonConfig fastJsonConfig = new FastJsonConfig();
                    // fastJsonConfig.setSerializerFeatures(
                    // SerializerFeature.WriteMapNullValue,
                    // SerializerFeature.WriteNullStringAsEmpty,
                    // SerializerFeature.WriteNullListAsEmpty,
                    // SerializerFeature.DisableCircularReferenceDetect);
                    // fastJsonHttpMessageConverter.setFastJsonConfig(fastJsonConfig);
                    restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(10 * 3)).build();

                    List<HttpMessageConverter<?>> messageConverters = restTemplate.getMessageConverters();

                    for (int i = 0; i < messageConverters.size(); i++) {
                        HttpMessageConverter<?> converter = messageConverters.get(i);
                        if (converter instanceof StringHttpMessageConverter) {
                            converter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
                            messageConverters.set(i, converter);
                            break;
                        }

                        // } else if (converter instanceof GsonHttpMessageConverter || converter
                        // instanceof MappingJackson2HttpMessageConverter) {
                        // converter = fastJsonHttpMessageConverter;
                        // messageConverters.set(i, converter);
                        // }
                    }
                }
            }
        }
        return restTemplate;
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
     * Get 请求，参数放在url中
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
    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) {
        log.info("restFul get请求开始 url={},params={},responseType={}", url, params, responseType);
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> responseEntity = RestTemplateUtils.getInstance().exchange(url, HttpMethod.GET, httpEntity, responseType);

            T result = responseEntity.getBody();
            log.info("restFul get请求结束 url={},params={},responseType={},result={}", url, params, responseType, result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul get请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul get请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
        }
    }

    public static <T> ResultDTO<T> get(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("restFul get请求开始 url={},params={},responseType={}", url, params, responseType);
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> responseEntity = RestTemplateUtils.getInstance().exchange(url, HttpMethod.GET, httpEntity, responseType);

            T result = responseEntity.getBody();
            log.info("restFul get请求结束 url={},params={},responseType={},result={}", url, params, responseType, result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul get请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul get请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
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
        log.info("restFul post请求开始 url={},params={},responseType={}", url, JSON.toJSONString(params), responseType);
        try {
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));

            T result = RestTemplateUtils.getInstance().postForObject(url, httpEntity, responseType);
            log.info("restFul post请求结束 url={},params={},responseType={},result={}", url, JSON.toJSONString(params), responseType, result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul post请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul post请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
        }
    }

    public static <T> ResultDTO<T> post(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) {
        log.info("restFul post请求开始 url={},params={},responseType={}", url, JSON.toJSONString(params), responseType);
        try {
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));

            T result = RestTemplateUtils.getInstance().exchange(url, HttpMethod.POST, httpEntity, responseType).getBody();
            log.info("restFul post请求结束 url={},params={},responseType={},result={}", url, JSON.toJSONString(params), responseType, result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul post请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul post请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
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
    public static <T> ResultDTO<T> put(String url, Map<String, Object> params, Class<T> responseType, Map<String, String> headers) throws Exception {
        log.info("restFul put请求开始 url={},params={},responseType={}", url, params, responseType);
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.PUT, httpEntity, responseType);
            log.info("restFul put请求结束 url={},params={},responseType={},exchange={}", url, params, responseType, exchange);
            T result = exchange.getBody();
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul put请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul put请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
        }
    }

    public static <T> ResultDTO<T> put(String url, Map<String, Object> params, ParameterizedTypeReference<T> responseType, Map<String, String> headers) throws Exception {
        log.info("restFul put请求开始 url={},params={},responseType={}", url, params, responseType);
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.PUT, httpEntity, responseType);
            log.info("restFul put请求结束 url={},params={},responseType={},exchange={}", url, params, responseType, exchange);
            T result = exchange.getBody();
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul put请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul put请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
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
        log.info("restFul delete请求开始 url={},params={},responseType={}", url, params, responseType);
        try {
            url = buildUrl(url, params);
            HttpEntity<Map<String, Object>> httpEntity = buildHttpEntity(params, buildHeaders(headers));
            ResponseEntity<T> exchange = RestTemplateUtils.getInstance().exchange(url, HttpMethod.DELETE, httpEntity, responseType, params);
            log.info("restFul delete请求结束 url={},params={},responseType={},serial={}", url, params, responseType, exchange);
            T result = exchange.getBody();
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul delete请求失败 url={},params={},responseType={},errMsg:{}", url, params, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul delete请求出现异常 url={},params={},responseType={}", url, params, responseType, e);
            return ResultDTO.fail(e.getMessage());
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
        log.info("restFul post xml 请求开始 url={},data={},responseType={}", url, data, responseType);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);

            HttpEntity<String> httpEntity = new HttpEntity<>(data,headers);
            T result = RestTemplateUtils.getInstance().postForObject(url, httpEntity, responseType);
            log.info("restFul post请求结束 result={}", result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul post xml 请求失败 url={},data={},responseType={},errMsg:{}", url, data, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul post xml 请求出现异常 url={},data={},responseType={}", url, data, responseType, e);
            return ResultDTO.fail(e.getMessage());
        }
    }

    public static <T> ResultDTO<T> postXml(String url, String data, ParameterizedTypeReference<T> responseType) {
        log.info("restFul post xml 请求开始 url={},data={},responseType={}", url, data, responseType);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            HttpEntity<String> httpEntity = new HttpEntity<>(data,headers);
            T result = RestTemplateUtils.getInstance().exchange(url, HttpMethod.POST, httpEntity, responseType).getBody();
            log.info("restFul post请求结束 result={}", result);
            return ResultDTO.success(result);
        } catch (HttpStatusCodeException e) {
            log.error("restFul post xml 请求失败 url={},data={},responseType={},errMsg:{}", url, data, responseType, e.getMessage());
            return handleFailResult(e);
        } catch (Exception e) {
            log.error("restFul post xml 请求出现异常 url={},data={},responseType={}", url, data, responseType, e);
            return ResultDTO.fail(e.getMessage());
        }
    }

    public static String buildUrl(String host, String path) {
        StringBuilder urlBuilder = new StringBuilder(host);

        if (host.endsWith("/") && path.startsWith("/")) {
            urlBuilder.append(path.substring(1));
        }
        urlBuilder.append(path);
        return urlBuilder.toString();
    }

    private static HttpEntity<Map<String, Object>> buildHttpEntity(Map<String, Object> params, HttpHeaders headers) {
        if (CollectionUtils.isNotEmpty(params)) {
            return new HttpEntity<>(params, headers);
        } else {

            return new HttpEntity<>(null, headers);
        }
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
        // 如果存在參數 放在url中
        if (null != params && !params.isEmpty()) {
            params.forEach(builder::queryParam);
        }
        return builder.build().toString();
    }

    private static <T> ResultDTO<T> handleFailResult(HttpStatusCodeException e) {
        JSONObject object = new JSONObject();
        object.put("rspBody", e.getResponseBodyAsString());
        object.put("errMsg", e.getMessage());
        return ResultDTO.fail(String.valueOf(e.getRawStatusCode()), object.toJSONString());
    }

}
