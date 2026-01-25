package org.buaa.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP客户端配置, 用于向量编码API调用
 */
@Configuration
public class HttpClientConfiguration {
    
    @Value("${embedding.api.url}")
    private String embeddingApiUrl;
    
    @Value("${embedding.api.key}")
    private String embeddingApiKey;
    
    /**
     * 创建用于向量编码的WebClient
     * 配置了大内存缓冲区以支持批量请求
     * 
     * @return WebClient实例
     */
    @Bean
    public WebClient embeddingWebClient() {
        // 配置16MB内存缓冲区
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
            .codecs(codecConfigurer -> codecConfigurer
                .defaultCodecs()
                .maxInMemorySize(calculateMaxBufferSize()))
            .build();

        return WebClient.builder()
            .baseUrl(embeddingApiUrl)
            .exchangeStrategies(exchangeStrategies)
            .defaultHeader(HttpHeaders.AUTHORIZATION, buildAuthHeader())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * 计算最大缓冲区大小（16MB）
     */
    private int calculateMaxBufferSize() {
        return 16 * 1024 * 1024;
    }

    /**
     * 构建认证头
     */
    private String buildAuthHeader() {
        return "Bearer " + embeddingApiKey;
    }
}
