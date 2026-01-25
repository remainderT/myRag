package buaa.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;

/**
 * Elasticsearch搜索引擎配置
 */
@Configuration
public class SearchEngineConfig {

    @Value("${elasticsearch.host}")
    private String esHost;

    @Value("${elasticsearch.port}")
    private int esPort;

    @Value("${elasticsearch.scheme:https}")
    private String protocol;

    @Value("${elasticsearch.username:elastic}")
    private String userName;

    @Value("${elasticsearch.password:changeme}")
    private String userPassword;

    /**
     * 构建Elasticsearch客户端实例
     * 
     * @return ES客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClientBuilder clientBuilder = RestClient.builder(
            new HttpHost(esHost, esPort, protocol)
        );

        // 配置身份认证
        if (isAuthenticationRequired()) {
            configureAuthentication(clientBuilder);
        }

        RestClient restClient = clientBuilder.build();
        RestClientTransport transport = new RestClientTransport(
            restClient, 
            new JacksonJsonpMapper()
        );

        return new ElasticsearchClient(transport);
    }

    /**
     * 判断是否需要身份认证
     */
    private boolean isAuthenticationRequired() {
        return userName != null && !userName.trim().isEmpty();
    }

    /**
     * 配置身份认证和SSL
     */
    private void configureAuthentication(RestClientBuilder builder) {
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY, 
            new UsernamePasswordCredentials(userName, userPassword)
        );

        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            configureSslContext(httpClientBuilder);
            return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        });
    }

    /**
     * 配置SSL上下文（开发环境跳过证书验证）
     */
    private void configureSslContext(org.apache.http.impl.nio.client.HttpAsyncClientBuilder httpClientBuilder) {
        try {
            SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
                .build();
            httpClientBuilder.setSSLContext(sslContext);
            httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
        } catch (Exception e) {
            // 忽略SSL配置错误
        }
    }
}
