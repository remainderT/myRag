package com.campus.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.campus.knowledge.dto.RetrievalMatch;
import com.campus.knowledge.model.DocumentRecord;
import com.campus.knowledge.model.IndexedContent;
import com.campus.knowledge.repository.DocumentRecordRepository;
import com.campus.knowledge.client.VectorEncodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能检索服务
 * 结合向量检索和文本匹配的混合搜索策略
 * 
 * @author campus-team
 */
@Service
public class SmartRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(SmartRetrieverService.class);
    private static final String KNOWLEDGE_INDEX = "knowledge_base";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private VectorEncodingService encodingService;

    @Autowired
    private DocumentRecordRepository documentRepository;

    /**
     * 执行混合检索
     * 
     * @param queryText 查询文本
     * @param topK 返回结果数量
     * @return 检索匹配结果列表
     */
    public List<RetrievalMatch> retrieve(String queryText, int topK) {
        try {
            log.debug("执行混合检索 - 查询: {}, K值: {}", queryText, topK);

            // 生成查询向量
            final List<Float> queryVector = generateQueryVector(queryText);
            
            // 向量生成失败则降级到纯文本检索
            if (queryVector == null) {
                log.warn("向量生成失败，降级为纯文本检索");
                return performTextOnlyRetrieval(queryText, topK);
            }

            // 执行混合检索
            List<RetrievalMatch> matches = performHybridRetrieval(queryText, queryVector, topK);
            
            // 补充文件名信息
            enrichWithFileNames(matches);
            
            return matches;
        } catch (Exception e) {
            log.error("检索失败", e);
            throw new RuntimeException("检索过程发生异常");
        }
    }

    /**
     * 执行混合检索
     */
    private List<RetrievalMatch> performHybridRetrieval(String query, 
                                                        List<Float> vector, 
                                                        int topK) throws Exception {
        int recallSize = topK * 30;  // 召回30倍候选集
        
        SearchResponse<IndexedContent> response = esClient.search(searchBuilder -> {
            // kNN向量召回
            searchBuilder.index(KNOWLEDGE_INDEX);
            searchBuilder.knn(knnBuilder -> knnBuilder
                .field("vectorEmbedding")
                .queryVector(vector)
                .k(recallSize)
                .numCandidates(recallSize)
            );

            // 文本匹配过滤
            searchBuilder.query(queryBuilder -> queryBuilder
                .match(matchBuilder -> matchBuilder
                    .field("textPayload")
                    .query(query)
                )
            );

            // BM25重排序
            searchBuilder.rescore(rescoreBuilder -> rescoreBuilder
                .windowSize(recallSize)
                .query(rescoreQueryBuilder -> rescoreQueryBuilder
                    .queryWeight(0.2)  // 向量得分权重
                    .rescoreQueryWeight(1.0)  // BM25得分权重
                    .query(innerQueryBuilder -> innerQueryBuilder
                        .match(matchBuilder -> matchBuilder
                            .field("textPayload")
                            .query(query)
                            .operator(Operator.And)
                        )
                    )
                )
            );
            
            searchBuilder.size(topK);
            return searchBuilder;
        }, IndexedContent.class);

        return response.hits().hits().stream()
            .filter(hit -> hit.source() != null)
            .map(hit -> new RetrievalMatch(
                hit.source().getSourceMd5(),
                hit.source().getSegmentNumber(),
                hit.source().getTextPayload(),
                hit.score()
            ))
            .collect(Collectors.toList());
    }

    /**
     * 纯文本检索（备用方案）
     */
    private List<RetrievalMatch> performTextOnlyRetrieval(String query, int topK) 
            throws Exception {
        SearchResponse<IndexedContent> response = esClient.search(searchBuilder ->
            searchBuilder
                .index(KNOWLEDGE_INDEX)
                .query(queryBuilder -> queryBuilder
                    .match(matchBuilder -> matchBuilder
                        .field("textPayload")
                        .query(query)
                    )
                )
                .size(topK),
            IndexedContent.class
        );

        List<RetrievalMatch> results = response.hits().hits().stream()
            .filter(hit -> hit.source() != null)
            .map(hit -> new RetrievalMatch(
                hit.source().getSourceMd5(),
                hit.source().getSegmentNumber(),
                hit.source().getTextPayload(),
                hit.score()
            ))
            .collect(Collectors.toList());
        
        enrichWithFileNames(results);
        return results;
    }

    /**
     * 生成查询向量
     */
    private List<Float> generateQueryVector(String text) {
        try {
            List<float[]> vectors = encodingService.encode(List.of(text));
            if (vectors == null || vectors.isEmpty()) {
                log.warn("向量编码返回空结果");
                return null;
            }
            
            float[] vectorArray = vectors.get(0);
            List<Float> vectorList = new ArrayList<>(vectorArray.length);
            for (float value : vectorArray) {
                vectorList.add(value);
            }
            return vectorList;
        } catch (Exception e) {
            log.error("向量生成失败", e);
            return null;
        }
    }

    /**
     * 为检索结果补充文件名
     */
    private void enrichWithFileNames(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        
        try {
            // 收集所有MD5
            Set<String> md5Set = matches.stream()
                .map(RetrievalMatch::getFileMd5)
                .collect(Collectors.toSet());
            
            // 批量查询文件信息
            List<DocumentRecord> documents = documentRepository.findByMd5HashIn(
                new ArrayList<>(md5Set)
            );
            
            // 构建MD5到文件名的映射
            Map<String, String> md5ToFileName = documents.stream()
                .collect(Collectors.toMap(
                    DocumentRecord::getMd5Hash,
                    DocumentRecord::getOriginalFileName
                ));
            
            // 填充文件名
            matches.forEach(match -> 
                match.setSourceFileName(md5ToFileName.get(match.getFileMd5()))
            );
        } catch (Exception e) {
            log.error("补充文件名失败", e);
        }
    }
}
