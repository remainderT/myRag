package com.campus.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.campus.knowledge.dto.ContentFragment;
import com.campus.knowledge.model.TextSegment;
import com.campus.knowledge.model.IndexedContent;
import com.campus.knowledge.repository.TextSegmentRepository;
import com.campus.knowledge.client.VectorEncodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 向量编码与索引服务
 * 负责将文本片段向量化并索引到Elasticsearch
 * 
 * @author campus-team
 */
@Service
public class EmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);
    private static final String INDEX_NAME = "knowledge_base";
    private static final String MODEL_VERSION = "text-embedding-v4";

    @Autowired
    private VectorEncodingService encodingService;

    @Autowired
    private ElasticsearchClient searchClient;

    @Autowired
    private TextSegmentRepository segmentRepository;

    /**
     * 对指定文档进行向量化并索引
     * 
     * @param documentMd5 文档MD5标识
     */
    public void indexDocument(String documentMd5) {
        try {
            log.info("启动文档索引流程: {}", documentMd5);
                       
            // 步骤1: 加载文本片段
            List<ContentFragment> fragments = loadTextFragments(documentMd5);
            if (fragments == null || fragments.isEmpty()) {
                log.warn("未发现文本片段: {}", documentMd5);
                return;
            }

            // 步骤2: 向量编码
            List<String> textContents = extractTextContents(fragments);
            List<float[]> vectorEmbeddings = encodingService.encode(textContents);

            // 步骤3: 构建索引文档
            List<IndexedContent> indexDocuments = buildIndexedDocuments(
                documentMd5, 
                fragments, 
                vectorEmbeddings
            );

            // 步骤4: 批量索引
            performBulkIndexing(indexDocuments);
            
            log.info("文档索引完成: {}, 片段数: {}", documentMd5, fragments.size());
        } catch (Exception e) {
            log.error("文档索引失败: {}", documentMd5, e);
            throw new RuntimeException("向量索引过程出错", e);
        }
    }
    
    /**
     * 批量索引到ES
     */
    private void performBulkIndexing(List<IndexedContent> documents) {
        try {
            log.info("执行批量索引，文档数: {}", documents.size());
            
            List<BulkOperation> operations = documents.stream()
                .map(this::createIndexOperation)
                .collect(Collectors.toList());

            BulkRequest bulkRequest = BulkRequest.of(builder -> 
                builder.operations(operations)
            );
            
            BulkResponse bulkResponse = searchClient.bulk(bulkRequest);
            
            if (bulkResponse.errors()) {
                handleBulkErrors(bulkResponse);
                throw new RuntimeException("部分文档索引失败");
            }
            
            log.info("批量索引成功，文档数: {}", documents.size());
        } catch (Exception e) {
            log.error("批量索引异常", e);
            throw new RuntimeException("索引操作失败", e);
        }
    }

    /**
     * 创建索引操作
     */
    private BulkOperation createIndexOperation(IndexedContent doc) {
        return BulkOperation.of(op -> op.index(idx -> idx
            .index(INDEX_NAME)
            .id(doc.getDocumentId())
            .document(doc)
        ));
    }

    /**
     * 处理批量操作错误
     */
    private void handleBulkErrors(BulkResponse response) {
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.error("索引失败 - 文档ID: {}, 原因: {}", 
                         item.id(), item.error().reason());
            }
        }
    }

    /**
     * 加载文本片段
     */
    private List<ContentFragment> loadTextFragments(String documentMd5) {
        List<TextSegment> segments = segmentRepository.findByDocumentMd5(documentMd5);
        return segments.stream()
            .map(seg -> new ContentFragment(seg.getFragmentIndex(), seg.getTextData()))
            .collect(Collectors.toList());
    }

    /**
     * 提取文本内容列表
     */
    private List<String> extractTextContents(List<ContentFragment> fragments) {
        return fragments.stream()
            .map(ContentFragment::getTextContent)
            .collect(Collectors.toList());
    }

    /**
     * 构建索引文档列表
     */
    private List<IndexedContent> buildIndexedDocuments(String documentMd5,
                                                       List<ContentFragment> fragments,
                                                       List<float[]> vectors) {
        return IntStream.range(0, fragments.size())
            .mapToObj(i -> createIndexedContent(
                documentMd5,
                fragments.get(i),
                vectors.get(i)
            ))
            .collect(Collectors.toList());
    }

    /**
     * 创建单个索引文档
     */
    private IndexedContent createIndexedContent(String documentMd5,
                                               ContentFragment fragment,
                                               float[] vector) {
        return new IndexedContent(
            generateDocumentId(),
            documentMd5,
            fragment.getFragmentId(),
            fragment.getTextContent(),
            vector,
            MODEL_VERSION
        );
    }

    /**
     * 生成唯一文档ID
     */
    private String generateDocumentId() {
        return UUID.randomUUID().toString();
    }
}
