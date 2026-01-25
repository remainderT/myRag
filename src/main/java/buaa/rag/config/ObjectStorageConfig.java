package buaa.rag.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置类
 */
@Configuration
public class ObjectStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageConfig.class);

    @Value("${minio.endpoint}")
    private String serviceEndpoint;

    @Value("${minio.accessKey}")
    private String accessKeyId;

    @Value("${minio.secretKey}")
    private String secretAccessKey;

    @Value("${minio.publicUrl}")
    private String publicAccessUrl;

    @Value("${minio.bucketName}")
    private String storageBucket;

    /**
     * 创建MinIO客户端Bean并初始化存储桶
     * 
     * @return MinIO客户端实例
     */
    @Bean
    public MinioClient minioClient() {
        MinioClient storageClient = buildMinioClient();
        ensureBucketExists(storageClient);
        return storageClient;
    }

    /**
     * 提供公共访问URL
     */
    @Bean
    public String minioPublicUrl() {
        return publicAccessUrl;
    }

    /**
     * 构建MinIO客户端
     */
    private MinioClient buildMinioClient() {
        return MinioClient.builder()
            .endpoint(serviceEndpoint)
            .credentials(accessKeyId, secretAccessKey)
            .build();
    }

    /**
     * 确保存储桶存在，不存在则创建
     * 
     * @param client MinIO客户端
     */
    private void ensureBucketExists(MinioClient client) {
        try {
            boolean bucketExists = client.bucketExists(
                BucketExistsArgs.builder()
                    .bucket(storageBucket)
                    .build()
            );

            if (!bucketExists) {
                createBucket(client);
                log.info("成功创建存储桶: {}", storageBucket);
            } else {
                log.info("存储桶已就绪: {}", storageBucket);
            }
        } catch (Exception e) {
            log.error("存储桶初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法初始化对象存储", e);
        }
    }

    /**
     * 创建新存储桶
     */
    private void createBucket(MinioClient client) throws Exception {
        client.makeBucket(
            MakeBucketArgs.builder()
                .bucket(storageBucket)
                .build()
        );
    }
}
