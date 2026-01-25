package buaa.rag.service;

import buaa.rag.client.LlmChatService;
import buaa.rag.config.RagConfiguration;
import buaa.rag.dto.QueryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询重写与HyDE生成服务
 */
@Service
public class QueryRefiner {

    private static final String DEFAULT_REWRITE_PROMPT = """
你是检索查询改写助手，请根据用户问题生成多条可用于检索的改写：
1. 每行只输出一条改写
2. 不要编号或引号
3. 保持简洁，避免增加新事实
""";

    private static final String DEFAULT_HYDE_PROMPT = """
请根据用户问题生成一个可能的理想答案，用于检索。
要求：
1. 使用简体中文
2. 80-150字
3. 不要编造具体数值或机构名称
""";

    private final LlmChatService llmChatService;
    private final RagConfiguration ragConfiguration;

    public QueryRefiner(LlmChatService llmChatService,
                        RagConfiguration ragConfiguration) {
        this.llmChatService = llmChatService;
        this.ragConfiguration = ragConfiguration;
    }

    public QueryPlan createPlan(String userQuery) {
        List<String> rewrites = generateRewrites(userQuery);
        String hydeAnswer = generateHydeAnswer(userQuery);
        return new QueryPlan(userQuery, rewrites, hydeAnswer);
    }

    private List<String> generateRewrites(String userQuery) {
        if (!ragConfiguration.getRewrite().isEnabled()) {
            return List.of();
        }

        String prompt = ragConfiguration.getRewrite().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_REWRITE_PROMPT;
        }

        String output = llmChatService.generateCompletion(prompt, userQuery, 256);
        return normalizeRewrites(output, ragConfiguration.getRewrite().getVariants());
    }

    private String generateHydeAnswer(String userQuery) {
        if (!ragConfiguration.getHyde().isEnabled()) {
            return null;
        }

        String prompt = ragConfiguration.getHyde().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_HYDE_PROMPT;
        }

        return llmChatService.generateCompletion(
            prompt,
            userQuery,
            ragConfiguration.getHyde().getMaxTokens()
        );
    }

    private List<String> normalizeRewrites(String output, int limit) {
        if (output == null || output.isBlank()) {
            return List.of();
        }

        String[] lines = output.split("\\r?\\n");
        Set<String> rewrites = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.replaceAll("^[-*\\d.、)]+", "").trim();
            if (!trimmed.isEmpty()) {
                rewrites.add(trimmed);
            }
        }

        List<String> result = new ArrayList<>(rewrites);
        if (limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }
}
