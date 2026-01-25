package buaa.rag.service;

import buaa.rag.client.LlmChatService;
import buaa.rag.config.LlmConfiguration;
import buaa.rag.config.RagConfiguration;
import buaa.rag.dto.CragDecision;
import buaa.rag.dto.RetrievalMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CRAG 检索质量自检与兜底策略
 */
@Service
public class CragService {

    private static final Logger log = LoggerFactory.getLogger(CragService.class);

    private static final String DEFAULT_PROMPT = """
你是检索质量评估器，请根据问题和候选片段判断是否足以回答。
只输出 JSON，字段如下：
{
  "action": "ANSWER|REFINE|CLARIFY|NO_ANSWER",
  "clarifyQuestion": "当需要澄清时给出一句问题"
}
如果问题本身模糊返回 CLARIFY；资料不足返回 REFINE 或 NO_ANSWER。
""";

    private static final String DEFAULT_CLARIFY_PROMPT = """
你是问答助手，请基于用户问题生成一个澄清问题，帮助补充场景信息。
要求：
1. 一句话
2. 不要给出答案
3. 使用简体中文
""";

    private final LlmChatService llmChatService;
    private final RagConfiguration ragConfiguration;
    private final LlmConfiguration llmConfiguration;
    private final ObjectMapper objectMapper;

    public CragService(LlmChatService llmChatService,
                       RagConfiguration ragConfiguration,
                       LlmConfiguration llmConfiguration) {
        this.llmChatService = llmChatService;
        this.ragConfiguration = ragConfiguration;
        this.llmConfiguration = llmConfiguration;
        this.objectMapper = new ObjectMapper();
    }

    public CragDecision evaluate(String query, List<RetrievalMatch> matches) {
        RagConfiguration.Crag config = ragConfiguration.getCrag();
        if (config == null || !config.isEnabled()) {
            return new CragDecision(CragDecision.Action.ANSWER, null);
        }

        if (matches == null || matches.isEmpty()) {
            if (isLikelyAmbiguous(query)) {
                return new CragDecision(CragDecision.Action.CLARIFY, buildClarifyQuestion(query, config));
            }
            return new CragDecision(CragDecision.Action.NO_ANSWER, noResultMessage());
        }

        if (config.isUseLlm() && shouldReviewWithLlm(matches, config)) {
            CragDecision decision = evaluateWithLlm(query, matches, config);
            if (decision != null) {
                return decision;
            }
        }

        if (isLowQuality(matches, config.getMinScore())) {
            return new CragDecision(CragDecision.Action.REFINE, null);
        }

        return new CragDecision(CragDecision.Action.ANSWER, null);
    }

    public boolean isLowQuality(List<RetrievalMatch> matches, double minScore) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        return score == null || score < minScore;
    }

    public String noResultMessage() {
        if (llmConfiguration != null && llmConfiguration.getPromptTemplate() != null) {
            String noResult = llmConfiguration.getPromptTemplate().getNoResultText();
            if (noResult != null && !noResult.isBlank()) {
                return noResult;
            }
        }
        return "暂无相关信息";
    }

    private boolean shouldReviewWithLlm(List<RetrievalMatch> matches, RagConfiguration.Crag config) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        if (score == null) {
            return true;
        }
        return score < config.getMinScore() * 1.5;
    }

    private CragDecision evaluateWithLlm(String query,
                                         List<RetrievalMatch> matches,
                                         RagConfiguration.Crag config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_PROMPT;
        }

        StringBuilder content = new StringBuilder();
        content.append("问题：").append(query).append("\n");
        content.append("候选片段：\n");
        int reviewTopK = Math.min(config.getReviewTopK(), matches.size());
        for (int i = 0; i < reviewTopK; i++) {
            RetrievalMatch match = matches.get(i);
            content.append(i + 1)
                .append("|")
                .append(truncate(match.getTextContent(), 240))
                .append("\n");
        }

        String output = llmChatService.generateCompletion(prompt, content.toString(), 256);
        if (output == null || output.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(output.trim());
            String actionText = node.path("action").asText("").toUpperCase();
            CragDecision.Action action = parseAction(actionText);
            if (action == null) {
                return null;
            }
            String clarifyQuestion = node.path("clarifyQuestion").asText(null);
            if (action == CragDecision.Action.CLARIFY) {
                if (clarifyQuestion == null || clarifyQuestion.isBlank()) {
                    clarifyQuestion = buildClarifyQuestion(query, config);
                }
                return new CragDecision(action, clarifyQuestion);
            }
            if (action == CragDecision.Action.NO_ANSWER) {
                return new CragDecision(action, noResultMessage());
            }
            return new CragDecision(action, null);
        } catch (Exception e) {
            log.debug("CRAG解析失败: {}", e.getMessage());
            return null;
        }
    }

    private CragDecision.Action parseAction(String actionText) {
        if (actionText == null || actionText.isBlank()) {
            return null;
        }
        try {
            return CragDecision.Action.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String buildClarifyQuestion(String query, RagConfiguration.Crag config) {
        if (!config.isUseLlm()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        String prompt = config.getClarifyPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_CLARIFY_PROMPT;
        }
        String output = llmChatService.generateCompletion(prompt, query, 64);
        if (output == null || output.isBlank()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        return output.trim();
    }

    private boolean isLikelyAmbiguous(String message) {
        if (message == null) {
            return true;
        }
        String trimmed = message.trim();
        if (trimmed.length() < 6) {
            return true;
        }
        return trimmed.contains("这个") || trimmed.contains("那个")
            || trimmed.contains("之前") || trimmed.contains("上面")
            || trimmed.contains("怎么弄") || trimmed.contains("怎么办");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
