package org.buaa.rag.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dto.MetadataFilter;
import org.buaa.rag.dto.QueryPlan;
import org.buaa.rag.service.QueryAnalysisService;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询分析服务实现
 * 包含查询改写/HyDE生成与元数据路由解析
 */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

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

    private static final String DEFAULT_ROUTING_PROMPT = """
你是检索路由助手，请从用户问题中抽取可能的元数据过滤条件。
只输出 JSON，字段如下：
{
  "department": "学院或部门",
  "docType": "文档类型",
  "policyYear": "年份",
  "tags": ["标签1","标签2"]
}
若无法确定请输出空字符串或空数组，不要输出解释。
""";

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Map<String, String> DOC_TYPE_KEYWORDS = new LinkedHashMap<>();
    private static final Map<String, String> DEPARTMENT_KEYWORDS = new LinkedHashMap<>();

    static {
        DOC_TYPE_KEYWORDS.put("综测", "综合测评");
        DOC_TYPE_KEYWORDS.put("综合测评", "综合测评");
        DOC_TYPE_KEYWORDS.put("评奖", "评奖评优");
        DOC_TYPE_KEYWORDS.put("奖学金", "评奖评优");
        DOC_TYPE_KEYWORDS.put("助学金", "评奖评优");
        DOC_TYPE_KEYWORDS.put("请假", "请假审批");
        DOC_TYPE_KEYWORDS.put("转专业", "转专业");
        DOC_TYPE_KEYWORDS.put("学籍", "学籍管理");
        DOC_TYPE_KEYWORDS.put("毕业", "毕业条件");
        DOC_TYPE_KEYWORDS.put("课程", "课程修读");
        DOC_TYPE_KEYWORDS.put("竞赛", "竞赛奖励");
        DOC_TYPE_KEYWORDS.put("挑战杯", "竞赛奖励");

        DEPARTMENT_KEYWORDS.put("计算机学院", "计算机学院");
        DEPARTMENT_KEYWORDS.put("自动化学院", "自动化学院");
        DEPARTMENT_KEYWORDS.put("航空学院", "航空学院");
        DEPARTMENT_KEYWORDS.put("电子信息工程学院", "电子信息工程学院");
        DEPARTMENT_KEYWORDS.put("软件学院", "软件学院");
        DEPARTMENT_KEYWORDS.put("材料学院", "材料学院");
        DEPARTMENT_KEYWORDS.put("机械学院", "机械学院");
        DEPARTMENT_KEYWORDS.put("经管学院", "经济管理学院");
        DEPARTMENT_KEYWORDS.put("人文学院", "人文社会科学学院");
        DEPARTMENT_KEYWORDS.put("数学学院", "数学科学学院");
        DEPARTMENT_KEYWORDS.put("物理学院", "物理学院");
    }

    private final LlmChat llmChat;
    private final RagConfiguration ragConfiguration;
    private final ObjectMapper objectMapper;

    public QueryAnalysisServiceImpl(LlmChat llmChat,
                                    RagConfiguration ragConfiguration) {
        this.llmChat = llmChat;
        this.ragConfiguration = ragConfiguration;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public QueryPlan createPlan(String userQuery) {
        List<String> rewrites = generateRewrites(userQuery);
        String hydeAnswer = generateHydeAnswer(userQuery);
        return new QueryPlan(userQuery, rewrites, hydeAnswer);
    }

    @Override
    public MetadataFilter resolveFilter(String query) {
        MetadataFilter heuristic = buildHeuristicFilter(query);
        RagConfiguration.Routing routing = ragConfiguration.getRouting();
        if (routing == null || !routing.isEnabled()) {
            return heuristic;
        }
        if (!routing.isUseLlm()) {
            return heuristic;
        }

        MetadataFilter llmFilter = buildLlmFilter(query, routing);
        return mergeFilters(heuristic, llmFilter, routing.getMaxTags());
    }

    private List<String> generateRewrites(String userQuery) {
        if (!ragConfiguration.getRewrite().isEnabled()) {
            return List.of();
        }

        String prompt = ragConfiguration.getRewrite().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_REWRITE_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, userQuery, 256);
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

        return llmChat.generateCompletion(
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

    private MetadataFilter buildHeuristicFilter(String query) {
        if (query == null || query.isBlank()) {
            return new MetadataFilter();
        }
        MetadataFilter filter = new MetadataFilter();
        String normalized = query.trim();

        Matcher matcher = YEAR_PATTERN.matcher(normalized);
        if (matcher.find()) {
            filter.setPolicyYear(matcher.group(1));
        }

        for (Map.Entry<String, String> entry : DOC_TYPE_KEYWORDS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                filter.setDocType(entry.getValue());
                break;
            }
        }

        for (Map.Entry<String, String> entry : DEPARTMENT_KEYWORDS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                filter.setDepartment(entry.getValue());
                break;
            }
        }

        List<String> tags = new ArrayList<>();
        if (normalized.contains("挑战杯")) {
            tags.add("挑战杯");
        }
        if (normalized.contains("竞赛")) {
            tags.add("竞赛");
        }
        if (!tags.isEmpty()) {
            filter.setTags(tags);
        }

        return filter;
    }

    private MetadataFilter buildLlmFilter(String query, RagConfiguration.Routing routing) {
        String prompt = routing.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_ROUTING_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, query, 256);
        if (output == null || output.isBlank()) {
            return new MetadataFilter();
        }

        try {
            JsonNode node = objectMapper.readTree(output.trim());
            MetadataFilter filter = new MetadataFilter();
            filter.setDepartment(asText(node, "department"));
            filter.setDocType(asText(node, "docType"));
            filter.setPolicyYear(asText(node, "policyYear"));
            filter.setTags(asTextList(node, "tags"));
            return filter;
        } catch (Exception ignored) {
            return new MetadataFilter();
        }
    }

    private String asText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private List<String> asTextList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        value.forEach(item -> {
            String text = item.asText();
            if (text != null && !text.isBlank()) {
                results.add(text.trim());
            }
        });
        return results;
    }

    private MetadataFilter mergeFilters(MetadataFilter base,
                                        MetadataFilter extra,
                                        int maxTags) {
        if (base == null) {
            return extra;
        }
        if (extra == null) {
            return base;
        }
        MetadataFilter merged = new MetadataFilter();
        merged.setDepartment(selectFirst(base.getDepartment(), extra.getDepartment()));
        merged.setDocType(selectFirst(base.getDocType(), extra.getDocType()));
        merged.setPolicyYear(selectFirst(base.getPolicyYear(), extra.getPolicyYear()));

        List<String> tags = new ArrayList<>();
        tags.addAll(base.normalizedTags());
        for (String tag : extra.normalizedTags()) {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        if (maxTags > 0 && tags.size() > maxTags) {
            tags = tags.subList(0, maxTags);
        }
        merged.setTags(tags);
        return merged;
    }

    private String selectFirst(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }
}
