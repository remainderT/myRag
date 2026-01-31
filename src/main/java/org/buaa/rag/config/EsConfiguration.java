package org.buaa.rag.config;

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
public class EsConfiguration {

    @Value("${elasticsearch.host}")
    private String esHost;

    @Value("${elasticsearch.port}")
    private int esPort;

    @Value("${elasticsearch.scheme:https}")
    private String protocol;

    @Value("${elasticsearch.username:elastic}")
    private String userName;

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

        RestClient restClient = clientBuilder.build();
        RestClientTransport transport = new RestClientTransport(
            restClient,
            new JacksonJsonpMapper()
        );

        return new ElasticsearchClient(transport);
    }
}
