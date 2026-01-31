package org.buaa.rag.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.IndexedContentDO;
import org.buaa.rag.dao.entity.MessageFeedbackDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.MessageFeedbackMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dto.MetadataFilter;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.service.SmartRetrieverService;
import org.buaa.rag.tool.VectorEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能检索服务实现
 * 结合向量检索和文本匹配的混合搜索策略
 */
@Service
public class SmartRetrieverServiceImpl implements SmartRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(SmartRetrieverServiceImpl.class);
    private static final int MAX_RECALL_SIZE = 300;

    @Value("${elasticsearch.index:knowledge_base}")
    private String knowledgeIndex;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private VectorEncoding encodingService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private RagConfiguration ragConfiguration;

    @Autowired
    private MessageFeedbackMapper feedbackRepository;

    @Autowired
    private MessageSourceMapper sourceRepository;

    @Override
    public List<RetrievalMatch> retrieve(String queryText, int topK) {
        return retrieve(queryText, topK, null);
    }

    @Override
    public List<RetrievalMatch> retrieve(String queryText, int topK, String userId) {
        return retrieve(queryText, topK, userId, null);
    }

    @Override
    public List<RetrievalMatch> retrieve(String queryText, int topK, String userId, MetadataFilter filter) {
        try {
            log.debug("执行混合检索 - 查询: {}, K值: {}", queryText, topK);

            // 生成查询向量
            final List<Float> queryVector = generateQueryVector(queryText);
            
            // 向量生成失败则降级到纯文本检索
            if (queryVector == null) {
                log.warn("向量生成失败，降级为纯文本检索");
                return performTextOnlyRetrieval(queryText, topK, userId, filter);
            }

            // 执行混合检索
            List<RetrievalMatch> matches = performHybridRetrieval(queryText, queryVector, topK);
            
            return filterAndEnrichMatches(matches, userId, topK, filter);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，返回空结果", knowledgeIndex);
                return Collections.emptyList();
            }
            log.error("检索失败", e);
            throw new RuntimeException("检索过程发生异常");
        }
    }

    @Override
    public List<RetrievalMatch> retrieveVectorOnly(String queryText, int topK, String userId) {
        return retrieveVectorOnly(queryText, topK, userId, null);
    }

    @Override
    public List<RetrievalMatch> retrieveVectorOnly(String queryText,
                                                   int topK,
                                                   String userId,
                                                   MetadataFilter filter) {
        try {
            List<Float> vector = generateQueryVector(queryText);
            if (vector == null) {
                return Collections.emptyList();
            }

            int recallSize = calculateRecallSize(queryText, topK);
            SearchResponse<IndexedContentDO> response = esClient.search(searchBuilder -> {
                searchBuilder.index(knowledgeIndex);
                searchBuilder.knn(knnBuilder -> knnBuilder
                    .field("vectorEmbedding")
                    .queryVector(vector)
                    .k(recallSize)
                    .numCandidates(recallSize)
                );
                searchBuilder.size(topK);
                return searchBuilder;
            }, IndexedContentDO.class);

            List<RetrievalMatch> results = response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> new RetrievalMatch(
                    hit.source().getSourceMd5(),
                    hit.source().getSegmentNumber(),
                    hit.source().getTextPayload(),
                    hit.score()
                ))
                .collect(Collectors.toList());

            return filterAndEnrichMatches(results, userId, topK, filter);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，向量检索返回空结果", knowledgeIndex);
                return Collections.emptyList();
            }
            log.error("向量检索失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<RetrievalMatch> retrieveTextOnly(String queryText,
                                                 int topK,
                                                 String userId,
                                                 MetadataFilter filter) {
        try {
            return performTextOnlyRetrieval(queryText, topK, userId, filter);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，文本检索返回空结果", knowledgeIndex);
                return Collections.emptyList();
            }
            log.error("文本检索失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void recordFeedback(Long messageId, String userId, int score, String comment) {
        MessageFeedbackDO feedback = new MessageFeedbackDO();
        feedback.setMessageId(messageId);
        feedback.setUserId(userId);
        feedback.setScore(score);
        feedback.setComment(comment);
        feedbackRepository.insert(feedback);
    }

    /**
     * 执行混合检索
     */
    private List<RetrievalMatch> performHybridRetrieval(String query, 
                                                        List<Float> vector, 
                                                        int topK) throws Exception {
        int recallSize = calculateRecallSize(query, topK);
        Operator matchOperator = resolveOperator(query);
        
        try {
            SearchResponse<IndexedContentDO> response = esClient.search(searchBuilder -> {
                // kNN向量召回
                searchBuilder.index(knowledgeIndex);
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
                        .operator(matchOperator)
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
                                .operator(matchOperator)
                            )
                        )
                    )
                );
                
                searchBuilder.size(topK);
                return searchBuilder;
            }, IndexedContentDO.class);

            return response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> new RetrievalMatch(
                    hit.source().getSourceMd5(),
                    hit.source().getSegmentNumber(),
                    hit.source().getTextPayload(),
                    hit.score()
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，混合检索返回空结果", knowledgeIndex);
                return Collections.emptyList();
            }
            throw e;
        }
    }

    private int calculateRecallSize(String query, int topK) {
        int factor = isShortQuery(query) ? 50 : 30;
        int recall = topK * factor;
        return Math.min(recall, MAX_RECALL_SIZE);
    }

    private Operator resolveOperator(String query) {
        return isShortQuery(query) ? Operator.Or : Operator.And;
    }

    private boolean isShortQuery(String query) {
        if (query == null) {
            return true;
        }
        return query.trim().length() <= 6;
    }

    /**
     * 纯文本检索（备用方案）
     */
    private List<RetrievalMatch> performTextOnlyRetrieval(String query,
                                                          int topK,
                                                          String userId,
                                                          MetadataFilter filter)
            throws Exception {
        try {
            SearchResponse<IndexedContentDO> response = esClient.search(searchBuilder ->
                searchBuilder
                    .index(knowledgeIndex)
                    .query(queryBuilder -> queryBuilder
                        .match(matchBuilder -> matchBuilder
                            .field("textPayload")
                            .query(query)
                        )
                    )
                    .size(topK),
                IndexedContentDO.class
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
            
            return filterAndEnrichMatches(results, userId, topK, filter);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，文本检索返回空结果", knowledgeIndex);
                return Collections.emptyList();
            }
            throw e;
        }
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
            List<DocumentDO> documentDOS = documentMapper.findByMd5HashIn(
                new ArrayList<>(md5Set)
            );

            // 构建MD5到文件名的映射
            Map<String, String> md5ToFileName = documentDOS.stream()
                .collect(Collectors.toMap(
                    DocumentDO::getMd5Hash,
                    DocumentDO::getOriginalFileName
                ));
            
            // 填充文件名
            matches.forEach(match -> 
                match.setSourceFileName(md5ToFileName.get(match.getFileMd5()))
            );
        } catch (Exception e) {
            log.error("补充文件名失败", e);
        }
    }

    /**
     * 权限过滤
     */
    private List<RetrievalMatch> filterMatchesByAccess(List<RetrievalMatch> matches,
                                                       String userId,
                                                       int topK) {
        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedUserId = normalizeUserId(userId);
        Set<String> allowedMd5 = resolveAccessibleDocuments(normalizedUserId);

        List<RetrievalMatch> filtered = matches.stream()
            .filter(match -> allowedMd5.contains(match.getFileMd5()))
            .collect(Collectors.toList());

        if (filtered.size() > topK) {
            return filtered.subList(0, topK);
        }

        return filtered;
    }

    private List<RetrievalMatch> filterAndEnrichMatches(List<RetrievalMatch> matches,
                                                        String userId,
                                                        int topK,
                                                        MetadataFilter filter) {
        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        List<RetrievalMatch> accessFiltered = filterMatchesByAccess(matches, userId, matches.size());
        if (accessFiltered.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, DocumentDO> recordMap = loadDocumentRecords(accessFiltered);
        List<RetrievalMatch> filtered = new ArrayList<>();

        for (RetrievalMatch match : accessFiltered) {
            DocumentDO record = recordMap.get(match.getFileMd5());
            if (record == null) {
                continue;
            }
            if (!matchesMetadata(record, filter)) {
                continue;
            }
            match.setSourceFileName(record.getOriginalFileName());
            filtered.add(match);
        }

        applyFeedbackBoost(filtered);

        if (filtered.size() > topK) {
            return filtered.subList(0, topK);
        }
        return filtered;
    }

    /**
     * 获取用户可访问的文档 MD5 列表。
     *
     * <p>仅允许 PUBLIC 或用户本人上传的文档。</p>
     *
     * @param userId 用户标识
     * @return 可访问文档的 MD5 集合
     */
    private Set<String> resolveAccessibleDocuments(String userId) {
        Set<String> md5Set = new HashSet<>();

        md5Set.addAll(documentMapper.findMd5HashByVisibility("PUBLIC"));
        md5Set.addAll(documentMapper.findMd5HashByOwnerId(userId));

        return md5Set;
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anonymous";
        }
        return userId;
    }

    private Map<String, DocumentDO> loadDocumentRecords(List<RetrievalMatch> matches) {
        Set<String> md5Set = matches.stream()
            .map(RetrievalMatch::getFileMd5)
            .collect(Collectors.toSet());
        List<DocumentDO> documentDOS = documentMapper.findByMd5HashIn(new ArrayList<>(md5Set));
        return documentDOS.stream()
            .collect(Collectors.toMap(DocumentDO::getMd5Hash, doc -> doc));
    }

    private boolean matchesMetadata(DocumentDO record, MetadataFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (!matchesField(record.getDepartment(), filter.getDepartment())) {
            return false;
        }
        if (!matchesField(record.getDocType(), filter.getDocType())) {
            return false;
        }
        if (!matchesField(record.getPolicyYear(), filter.getPolicyYear())) {
            return false;
        }
        List<String> tags = parseTags(record.getTags());
        List<String> filterTags = filter.normalizedTags();
        if (!filterTags.isEmpty() && tags.isEmpty()) {
            return false;
        }
        if (!filterTags.isEmpty()) {
            boolean anyMatch = false;
            for (String filterTag : filterTags) {
                if (matchesToken(tags, filterTag)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesField(String value, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        String normalizedExpected = expected.trim().toLowerCase(Locale.ROOT);
        return normalizedValue.contains(normalizedExpected);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = tags.split("[,，;；]");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return results;
    }

    private boolean matchesToken(List<String> tokens, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String normalized = expected.trim().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIndexMissing(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null) {
            String lowered = message.toLowerCase(Locale.ROOT);
            if (lowered.contains("index_not_found_exception") || lowered.contains("index_not_found")) {
                return true;
            }
        }
        return isIndexMissing(error.getCause());
    }

    private void applyFeedbackBoost(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        RagConfiguration.Feedback config = ragConfiguration.getFeedback();
        if (config == null || !config.isEnabled()) {
            return;
        }

        Set<String> md5Set = matches.stream()
            .map(RetrievalMatch::getFileMd5)
            .collect(Collectors.toSet());
        Map<String, Double> boostMap = loadBoostMap(md5Set, config.getMaxBoost());
        if (boostMap.isEmpty()) {
            return;
        }

        for (RetrievalMatch match : matches) {
            double baseScore = match.getRelevanceScore() != null ? match.getRelevanceScore() : 0.0;
            double boost = boostMap.getOrDefault(match.getFileMd5(), 0.0);
            match.setRelevanceScore(baseScore * (1 + boost));
        }

        matches.sort((a, b) -> Double.compare(
            b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
            a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
    }

    private Map<String, Double> loadBoostMap(Set<String> md5Set, double maxBoost) {
        if (md5Set == null || md5Set.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = sourceRepository.findAverageScoreByDocumentMd5In(md5Set);
        Map<String, Double> boostMap = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            String md5 = row[0] != null ? row[0].toString() : null;
            if (md5 == null) {
                continue;
            }
            Double avgScore = row[1] instanceof Number ? ((Number) row[1]).doubleValue() : null;
            if (avgScore == null) {
                continue;
            }
            double centered = (avgScore - 3.0) / 2.0;
            double boost = Math.max(-maxBoost, Math.min(maxBoost, centered * maxBoost));
            boostMap.put(md5, boost);
        }
        return boostMap;
    }
}
