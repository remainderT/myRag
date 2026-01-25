package buaa.rag.service;

import buaa.rag.model.Document;
import buaa.rag.repository.DocumentRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 文档后台摄取服务。
 *
 * <p>在异步线程中完成文档解析、分块与向量索引，避免阻塞上传请求。</p>
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    @Autowired
    private MinioClient storageClient;

    @Autowired
    private DocumentProcessor documentProcessor;

    @Autowired
    private EmbeddingIndexer embeddingIndexer;

    @Autowired
    private DocumentRepository documentRepository;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 异步摄取文档。
     *
     * @param documentMd5 文档MD5
     * @param originalFileName 原始文件名
     */
    @Async("documentIngestionExecutor")
    @Transactional
    public void ingestDocumentAsync(String documentMd5, String originalFileName) {
        String objectPath = String.format("uploads/%s/%s", documentMd5, originalFileName);
        log.info("异步摄取开始: {} -> {}", documentMd5, objectPath);

        if (!markProcessing(documentMd5)) {
            log.warn("文档记录不存在，跳过摄取: {}", documentMd5);
            return;
        }

        try (InputStream inputStream = storageClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectPath)
                .build()
        )) {
            documentProcessor.processAndStore(documentMd5, inputStream);
        } catch (Exception e) {
            markFailed(documentMd5, "文档解析失败", e);
            return;
        }

        try {
            embeddingIndexer.indexDocument(documentMd5);
            markCompleted(documentMd5);
            log.info("文档摄取完成: {}", documentMd5);
        } catch (Exception e) {
            markFailed(documentMd5, "向量索引失败", e);
        }
    }

    /**
     * 标记文档为处理中。
     *
     * @param documentMd5 文档MD5
     * @return 是否成功找到并更新记录
     */
    private boolean markProcessing(String documentMd5) {
        Optional<Document> recordOpt = documentRepository.findByMd5Hash(documentMd5);
        if (recordOpt.isEmpty()) {
            return false;
        }
        Document record = recordOpt.get();
        record.setProcessingStatus(0);
        record.setProcessedAt(null);
        documentRepository.save(record);
        return true;
    }

    /**
     * 标记文档处理完成。
     *
     * @param documentMd5 文档MD5
     */
    private void markCompleted(String documentMd5) {
        documentRepository.findByMd5Hash(documentMd5).ifPresent(record -> {
            record.setProcessingStatus(1);
            record.setProcessedAt(LocalDateTime.now());
            documentRepository.save(record);
        });
    }

    /**
     * 标记文档处理失败。
     *
     * @param documentMd5 文档MD5
     * @param message 错误信息
     * @param error 异常对象
     */
    private void markFailed(String documentMd5, String message, Exception error) {
        log.error("{}: {}", message, documentMd5, error);
        documentRepository.findByMd5Hash(documentMd5).ifPresent(record -> {
            record.setProcessingStatus(-1);
            record.setProcessedAt(LocalDateTime.now());
            documentRepository.save(record);
        });
    }
}
