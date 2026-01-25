package buaa.rag.service;

import buaa.rag.client.LlmChatService;
import buaa.rag.config.RagConfiguration;
import buaa.rag.dto.RetrievalMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索重排服务（LLM reranker）
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    private static final String DEFAULT_PROMPT = """
你是检索重排助手，请根据用户问题为候选片段打相关度分数。
要求：
1. 评分范围 0.0-1.0，越高越相关
2. 只输出 “id:score” 每行一条
3. 不要输出多余解释或符号
""";

    private final LlmChatService llmChatService;
    private final RagConfiguration ragConfiguration;

    public RerankerService(LlmChatService llmChatService,
                           RagConfiguration ragConfiguration) {
        this.llmChatService = llmChatService;
        this.ragConfiguration = ragConfiguration;
    }

    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> matches, int topK) {
        if (matches == null || matches.size() <= 1) {
            return matches;
        }
        RagConfiguration.Rerank config = ragConfiguration.getRerank();
        if (config == null || !config.isEnabled()) {
            return matches;
        }

        int candidateLimit = Math.min(config.getMaxCandidates(), matches.size());
        List<RetrievalMatch> candidates = new ArrayList<>(matches.subList(0, candidateLimit));
        String prompt = buildPrompt(query, candidates, config);

        String output = llmChatService.generateCompletion(
            resolvePrompt(config),
            prompt,
            256
        );

        Map<Integer, Double> scoreMap = parseScores(output);
        if (scoreMap.isEmpty()) {
            log.debug("重排输出为空，保持原排序");
            return matches;
        }

        for (int i = 0; i < candidates.size(); i++) {
            RetrievalMatch match = candidates.get(i);
            Double score = scoreMap.get(i + 1);
            if (score != null) {
                match.setRelevanceScore(score);
            }
        }

        candidates.sort(Comparator.comparingDouble(
            (RetrievalMatch match) -> match.getRelevanceScore() != null ? match.getRelevanceScore() : 0.0
        ).reversed());

        List<RetrievalMatch> reranked = new ArrayList<>(candidates);
        if (matches.size() > candidateLimit) {
            reranked.addAll(matches.subList(candidateLimit, matches.size()));
        }

        if (reranked.size() > topK) {
            return reranked.subList(0, topK);
        }
        return reranked;
    }

    private String buildPrompt(String query,
                               List<RetrievalMatch> candidates,
                               RagConfiguration.Rerank config) {
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(query).append("\n");
        builder.append("候选片段：\n");
        int snippetLength = config.getSnippetLength();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalMatch match = candidates.get(i);
            String snippet = buildSnippet(match.getTextContent(), snippetLength);
            String fileName = match.getSourceFileName() != null ? match.getSourceFileName() : "未知来源";
            builder.append(i + 1)
                .append("|")
                .append(fileName)
                .append("|")
                .append(snippet)
                .append("\n");
        }
        builder.append("请输出评分：\n");
        return builder.toString();
    }

    private String buildSnippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private String resolvePrompt(RagConfiguration.Rerank config) {
        if (config.getPrompt() != null && !config.getPrompt().isBlank()) {
            return config.getPrompt();
        }
        return DEFAULT_PROMPT;
    }

    private Map<Integer, Double> parseScores(String output) {
        Map<Integer, Double> scores = new HashMap<>();
        if (output == null || output.isBlank()) {
            return scores;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("[:：]");
            if (parts.length < 2) {
                continue;
            }
            try {
                int id = Integer.parseInt(parts[0].trim());
                double score = Double.parseDouble(parts[1].trim());
                if (score < 0) {
                    score = 0;
                } else if (score > 1) {
                    score = 1;
                }
                scores.put(id, score);
            } catch (NumberFormatException ignored) {
                String normalized = trimmed.toLowerCase(Locale.ROOT);
                if (normalized.contains("score")) {
                    continue;
                }
            }
        }
        return scores;
    }
}
