package com.smartinterview.config;

import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticSearchConfig {

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:http}")
    private String scheme;

    @Value("${elasticsearch.max-connect-total:20}")
    private int maxConnectTotal;

    @Value("${elasticsearch.max-connect-per-route:10}")
    private int maxConnectPerRoute;

    @Value("${elasticsearch.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${elasticsearch.socket-timeout:10000}")
    private int socketTimeout;

    @Value("${elasticsearch.keep-alive-time:30000}")
    private int keepAliveTime;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        // 1. 基础连接信息设置
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));

        // 2. 超时时间配置 (Connect Timeout, Socket Timeout)
        builder.setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
            @Override
            public RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
                return requestConfigBuilder
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(socketTimeout);
            }
        });

        // 3. 异步 HTTP 连接池配置 (Max Conn Total, Max Conn Per Route, Keep Alive)
        builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
            @Override
            public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                return httpClientBuilder
                        .setMaxConnTotal(maxConnectTotal)
                        .setMaxConnPerRoute(maxConnectPerRoute)
                        .setKeepAliveStrategy((response, context) -> keepAliveTime);
            }
        });

        return new RestHighLevelClient(builder);
    }
}