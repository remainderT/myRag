package buaa.rag.service;

import buaa.rag.client.LlmChatService;
import buaa.rag.client.VectorEncodingService;
import buaa.rag.config.LlmConfiguration;
import buaa.rag.config.RagConfiguration;
import buaa.rag.dto.EvaluationItem;
import buaa.rag.dto.EvaluationResultDto;
import buaa.rag.dto.EvaluationRunResponse;
import buaa.rag.dto.MetadataFilter;
import buaa.rag.dto.QueryPlan;
import buaa.rag.dto.RetrievalMatch;
import buaa.rag.model.EvalResult;
import buaa.rag.model.EvalRun;
import buaa.rag.repository.EvalResultRepository;
import buaa.rag.repository.EvalRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线评测服务
 */
@Service
public class EvalService {

    private static final int DEFAULT_RETRIEVAL_K = 5;
    private static final int MAX_RETRIEVAL_K = 10;
    private static final String DATASET_PATH = "eval/benchmarks.json";

    private final SmartRetrieverService retrieverService;
    private final QueryRefiner queryRefiner;
    private final RerankerService rerankerService;
    private final QueryRoutingService routingService;
    private final RagConfiguration ragConfiguration;
    private final LlmChatService llmChatService;
    private final LlmConfiguration llmConfiguration;
    private final VectorEncodingService encodingService;
    private final EvalRunRepository runRepository;
    private final EvalResultRepository resultRepository;
    private final ObjectMapper objectMapper;

    public EvalService(SmartRetrieverService retrieverService,
                       QueryRefiner queryRefiner,
                       RerankerService rerankerService,
                       QueryRoutingService routingService,
                       RagConfiguration ragConfiguration,
                       LlmChatService llmChatService,
                       LlmConfiguration llmConfiguration,
                       VectorEncodingService encodingService,
                       EvalRunRepository runRepository,
                       EvalResultRepository resultRepository) {
        this.retrieverService = retrieverService;
        this.queryRefiner = queryRefiner;
        this.rerankerService = rerankerService;
        this.routingService = routingService;
        this.ragConfiguration = ragConfiguration;
        this.llmChatService = llmChatService;
        this.llmConfiguration = llmConfiguration;
        this.encodingService = encodingService;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.objectMapper = new ObjectMapper();
    }

    public EvaluationRunResponse runEvaluation(boolean withAnswer) {
        List<EvaluationItem> items = loadDataset();
        if (items.isEmpty()) {
            return new EvaluationRunResponse(null, 0, 0.0, null, List.of());
        }

        EvalRun run = new EvalRun();
        runRepository.save(run);

        int total = items.size();
        int hitCount = 0;
        double similaritySum = 0.0;
        int similarityCount = 0;
        List<EvaluationResultDto> results = new ArrayList<>();

        for (EvaluationItem item : items) {
            String question = item.getQuestion();
            MetadataFilter filter = routingService.resolveFilter(question);
            List<RetrievalMatch> matches = retrieveMatches(question, filter);
            boolean hit = evaluateHit(matches, item);
            if (hit) {
                hitCount += 1;
            }

            String answer = "";
            if (withAnswer) {
                answer = generateAnswer(question, matches);
            }

            Double similarity = null;
            if (withAnswer && item.getExpectedAnswer() != null && !item.getExpectedAnswer().isBlank()) {
                similarity = computeSimilarity(item.getExpectedAnswer(), answer);
                if (similarity != null) {
                    similaritySum += similarity;
                    similarityCount += 1;
                }
            }

            saveResult(run.getId(), item, question, answer, hit, similarity);
            results.add(new EvaluationResultDto(item.getId(), question, hit, similarity, answer));
        }

        double hitRate = total == 0 ? 0.0 : (double) hitCount / total;
        Double avgSimilarity = similarityCount == 0 ? null : similaritySum / similarityCount;

        run.setTotalItems(total);
        run.setHitRate(hitRate);
        run.setAvgSimilarity(avgSimilarity);
        run.setFinishedAt(java.time.LocalDateTime.now());
        runRepository.save(run);

        return new EvaluationRunResponse(run.getId(), total, hitRate, avgSimilarity, results);
    }

    private List<EvaluationItem> loadDataset() {
        try {
            ClassPathResource resource = new ClassPathResource(DATASET_PATH);
            if (!resource.exists()) {
                return Collections.emptyList();
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<EvaluationItem>>() {}
                );
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<RetrievalMatch> retrieveMatches(String question, MetadataFilter filter) {
        int topK = determineRetrievalK(question);
        QueryPlan plan = queryRefiner.createPlan(question);
        List<List<RetrievalMatch>> resultSets = new ArrayList<>();

        resultSets.add(retrieverService.retrieve(question, topK, "eval", filter));

        int remainingQueries = ragConfiguration.getFusion().getMaxQueries() - 1;
        if (ragConfiguration.getHyde().isEnabled()) {
            remainingQueries -= 1;
        }

        if (ragConfiguration.getRewrite().isEnabled() && remainingQueries > 0) {
            List<String> rewrites = plan.getRewrittenQueries();
            if (rewrites != null && !rewrites.isEmpty()) {
                int limit = Math.min(remainingQueries, rewrites.size());
                for (int i = 0; i < limit; i++) {
                    resultSets.add(retrieverService.retrieve(rewrites.get(i), topK, "eval", filter));
                }
            }
        }

        if (ragConfiguration.getHyde().isEnabled()) {
            String hydeAnswer = plan.getHydeAnswer();
            if (hydeAnswer != null && !hydeAnswer.isBlank()) {
                resultSets.add(retrieverService.retrieveVectorOnly(hydeAnswer, topK, "eval", filter));
            }
        }

        List<RetrievalMatch> fused = (!ragConfiguration.getFusion().isEnabled() || resultSets.size() == 1)
            ? resultSets.get(0)
            : fuseByRrf(resultSets, topK, ragConfiguration.getFusion().getRrfK());
        return rerankerService.rerank(question, fused, topK);
    }

    private int determineRetrievalK(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_RETRIEVAL_K;
        }
        int length = message.trim().length();
        int k = DEFAULT_RETRIEVAL_K;
        if (length > 40) {
            k += 3;
        } else if (length > 20) {
            k += 1;
        }
        if (containsMultiIntentHint(message)) {
            k += 2;
        }
        return Math.min(k, MAX_RETRIEVAL_K);
    }

    private boolean containsMultiIntentHint(String message) {
        return message.contains("以及") || message.contains("和") || message.contains("、");
    }

    private List<RetrievalMatch> fuseByRrf(List<List<RetrievalMatch>> resultSets,
                                           int topK,
                                           int rrfK) {
        Map<String, RetrievalMatch> bestMatch = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (List<RetrievalMatch> set : resultSets) {
            if (set == null) {
                continue;
            }
            for (int i = 0; i < set.size(); i++) {
                RetrievalMatch match = set.get(i);
                if (match == null) {
                    continue;
                }
                String key = buildMatchKey(match);
                double score = 1.0 / (rrfK + i + 1);
                scores.merge(key, score, Double::sum);
                bestMatch.putIfAbsent(key, match);
            }
        }

        List<RetrievalMatch> fused = new ArrayList<>();
        for (Map.Entry<String, RetrievalMatch> entry : bestMatch.entrySet()) {
            RetrievalMatch match = entry.getValue();
            Double score = scores.get(entry.getKey());
            match.setRelevanceScore(score);
            fused.add(match);
        }

        fused.sort((a, b) -> Double.compare(
            b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
            a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));

        if (fused.size() > topK) {
            return fused.subList(0, topK);
        }
        return fused;
    }

    private String buildMatchKey(RetrievalMatch match) {
        String md5 = match.getFileMd5() != null ? match.getFileMd5() : "unknown";
        String chunk = match.getChunkId() != null ? match.getChunkId().toString() : "0";
        return md5 + ":" + chunk;
    }

    private boolean evaluateHit(List<RetrievalMatch> matches, EvaluationItem item) {
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        List<String> keywords = item.getExpectedKeywords() != null ? item.getExpectedKeywords() : List.of();
        List<String> sources = item.getExpectedSources() != null ? item.getExpectedSources() : List.of();

        for (RetrievalMatch match : matches) {
            String text = match.getTextContent() != null ? match.getTextContent().toLowerCase(Locale.ROOT) : "";
            String sourceName = match.getSourceFileName() != null
                ? match.getSourceFileName().toLowerCase(Locale.ROOT) : "";

            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank()
                    && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }

            for (String expectedSource : sources) {
                if (expectedSource != null && !expectedSource.isBlank()
                    && sourceName.contains(expectedSource.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateAnswer(String question, List<RetrievalMatch> matches) {
        String context = buildReferenceContext(matches);
        String systemPrompt = buildSystemPrompt(context);
        return llmChatService.generateCompletion(systemPrompt, question, null);
    }

    private String buildSystemPrompt(String context) {
        LlmConfiguration.PromptTemplate prompt = llmConfiguration.getPromptTemplate();
        StringBuilder builder = new StringBuilder();
        if (prompt != null && prompt.getRules() != null && !prompt.getRules().isBlank()) {
            builder.append(prompt.getRules()).append("\n\n");
        }

        String refStart = prompt != null ? prompt.getRefStart() : "<<参考资料开始>>";
        String refEnd = prompt != null ? prompt.getRefEnd() : "<<参考资料结束>>";
        String noResult = prompt != null ? prompt.getNoResultText() : "暂无相关信息";

        builder.append(refStart).append("\n");
        if (context != null && !context.isBlank()) {
            builder.append(context);
        } else {
            builder.append(noResult).append("\n");
        }
        builder.append("\n").append(refEnd);
        return builder.toString();
    }

    private String buildReferenceContext(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        StringBuilder contextBuilder = new StringBuilder();
        int limit = Math.min(matches.size(), 5);
        for (int i = 0; i < limit; i++) {
            RetrievalMatch match = matches.get(i);
            String textSnippet = truncateText(match.getTextContent(), 280);
            String sourceLabel = match.getSourceFileName() != null
                ? match.getSourceFileName()
                : "未知来源";
            contextBuilder.append(String.format(
                "[%d] (%s) %s\n",
                i + 1,
                sourceLabel,
                textSnippet
            ));
        }
        return contextBuilder.toString();
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private Double computeSimilarity(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) {
            return null;
        }
        try {
            List<float[]> vectors = encodingService.encode(List.of(expected, actual));
            if (vectors == null || vectors.size() < 2) {
                return null;
            }
            return cosineSimilarity(vectors.get(0), vectors.get(1));
        } catch (Exception e) {
            return null;
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int length = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private void saveResult(Long runId,
                            EvaluationItem item,
                            String question,
                            String answer,
                            boolean hit,
                            Double similarity) {
        EvalResult result = new EvalResult();
        result.setRunId(runId);
        result.setItemId(item.getId());
        result.setQuestion(question);
        result.setExpectedAnswer(item.getExpectedAnswer());
        result.setActualAnswer(answer);
        result.setHit(hit);
        result.setSimilarity(similarity);
        resultRepository.save(result);
    }
}
