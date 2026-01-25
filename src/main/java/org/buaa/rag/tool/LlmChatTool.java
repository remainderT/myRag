package org.buaa.rag.tool;

import org.buaa.rag.config.LlmConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大语言模型聊天服务
 * 负责与LLM API进行流式交互
 */
@Service
public class LlmChatTool {

    private static final Logger log = LoggerFactory.getLogger(LlmChatTool.class);
    
    private final WebClient httpClient;
    private final String apiToken;
    private final String modelIdentifier;
    private final LlmConfiguration llmConfiguration;
    private final ObjectMapper jsonMapper;
    
    public LlmChatTool(@Value("${deepseek.api.url}") String baseUrl,
                       @Value("${deepseek.api.key}") String token,
                       @Value("${deepseek.api.model}") String model,
                       LlmConfiguration config) {
        this.httpClient = buildWebClient(baseUrl, token);
        this.apiToken = token;
        this.modelIdentifier = model;
        this.llmConfiguration = config;
        this.jsonMapper = new ObjectMapper();
    }
    
    /**
     * 构建WebClient
     */
    private WebClient buildWebClient(String baseUrl, String token) {
        WebClient.Builder clientBuilder = WebClient.builder().baseUrl(baseUrl);
        
        if (isValidToken(token)) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        
        return clientBuilder.build();
    }

    /**
     * 验证token是否有效
     */
    private boolean isValidToken(String token) {
        return token != null && !token.trim().isEmpty();
    }
    
    /**
     * 流式响应方法
     * 
     * @param userQuery 用户查询
     * @param referenceContext 参考上下文
     * @param conversationHistory 对话历史
     * @param chunkHandler 处理每个响应块的回调
     * @param errorHandler 错误处理回调
     */
    public void streamResponse(String userQuery, 
                               String referenceContext,
                               List<Map<String, String>> conversationHistory,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        
        Map<String, Object> requestPayload = constructRequestPayload(
            userQuery, 
            referenceContext, 
            conversationHistory
        );
        
        log.info("发起流式请求，模型: {}", modelIdentifier);
        
        var completionFlag = new java.util.concurrent.atomic.AtomicBoolean(false);

        httpClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(requestPayload)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnComplete(() -> notifyCompletion(completionFlag, completionHandler))
            .subscribe(
                chunk -> {
                    boolean isDone = parseAndHandleChunk(chunk, chunkHandler);
                    if (isDone) {
                        notifyCompletion(completionFlag, completionHandler);
                    }
                },
                error -> {
                    if (errorHandler != null) {
                        errorHandler.accept(error);
                    }
                }
            );
    }

    /**
     * 生成一次性响应
     *
     * @param systemPrompt 系统提示
     * @param userPrompt 用户提示
     * @param maxTokens 最大生成token数
     * @return 模型输出内容
     */
    public String generateCompletion(String systemPrompt,
                                     String userPrompt,
                                     Integer maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (userPrompt != null) {
            messages.add(Map.of("role", "user", "content", userPrompt));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", modelIdentifier);
        payload.put("messages", messages);
        payload.put("stream", false);
        applyGenerationParameters(payload);
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }

        try {
            String response = httpClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
            if (response == null || response.isBlank()) {
                return "";
            }
            return extractMessageContent(response);
        } catch (Exception e) {
            log.debug("生成请求失败: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * 构造请求负载
     */
    private Map<String, Object> constructRequestPayload(String userQuery, 
                                                        String context,
                                                        List<Map<String, String>> history) {
        log.debug("构造请求 - 查询: {}, 上下文大小: {}, 历史记录: {}", 
                 userQuery, 
                 context != null ? context.length() : 0, 
                 history != null ? history.size() : 0);
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", modelIdentifier);
        payload.put("messages", assembleMessageList(userQuery, context, history));
        payload.put("stream", true);
        
        // 添加生成参数
        applyGenerationParameters(payload);
        
        return payload;
    }
    
    /**
     * 应用生成参数
     */
    private void applyGenerationParameters(Map<String, Object> payload) {
        LlmConfiguration.GenerationParams params = llmConfiguration.getGenerationParams();
        
        if (params.getTemperature() != null) {
            payload.put("temperature", params.getTemperature());
        }
        if (params.getTopP() != null) {
            payload.put("top_p", params.getTopP());
        }
        if (params.getMaxTokens() != null) {
            payload.put("max_tokens", params.getMaxTokens());
        }
    }
    
    /**
     * 组装消息列表
     */
    private List<Map<String, String>> assembleMessageList(String userQuery,
                                                          String context,
                                                          List<Map<String, String>> history) {
        List<Map<String, String>> messageList = new ArrayList<>();

        // 添加系统消息
        messageList.add(buildSystemMessage(context));
        log.debug("系统消息已构建");

        // 添加历史消息
        if (history != null && !history.isEmpty()) {
            messageList.addAll(history);
            log.debug("添加了 {} 条历史消息", history.size());
        }

        // 添加当前用户查询
        messageList.add(Map.of("role", "user", "content", userQuery));

        return messageList;
    }
    
    /**
     * 构建系统消息
     */
    private Map<String, String> buildSystemMessage(String context) {
        LlmConfiguration.PromptTemplate template = llmConfiguration.getPromptTemplate();

        StringBuilder systemMessageBuilder = new StringBuilder();
        
        // 添加规则
        String rules = template.getRules();
        if (rules != null && !rules.isEmpty()) {
            systemMessageBuilder.append(rules).append("\n\n");
        }

        // 添加参考资料部分
        String refStartMarker = getOrDefault(template.getRefStart(), "<<参考资料开始>>");
        String refEndMarker = getOrDefault(template.getRefEnd(), "<<参考资料结束>>");
        
        systemMessageBuilder.append(refStartMarker).append("\n");

        if (context != null && !context.isEmpty()) {
            systemMessageBuilder.append(context);
        } else {
            String noResultPlaceholder = getOrDefault(
                template.getNoResultText(), 
                "当前未检索到相关资料"
            );
            systemMessageBuilder.append(noResultPlaceholder).append("\n");
        }

        systemMessageBuilder.append(refEndMarker);

        return Map.of("role", "system", "content", systemMessageBuilder.toString());
    }

    /**
     * 获取值或默认值
     */
    private String getOrDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * 解析并处理响应块
     */
    private boolean parseAndHandleChunk(String chunk, Consumer<String> handler) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }

        boolean completed = false;
        try {
            String[] lines = chunk.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                    continue;
                }

                if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                    continue;
                }

                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring("data:".length()).trim();
                }

                if (trimmed.isEmpty()) {
                    continue;
                }

                if ("[DONE]".equals(trimmed)) {
                    completed = true;
                    continue;
                }

                String content = extractContentFromChunk(trimmed);
                if (content != null && !content.isEmpty() && handler != null) {
                    handler.accept(content);
                }
            }
        } catch (Exception e) {
            log.debug("解析响应块失败: {}", e.getMessage());
        }
        return completed;
    }

    /**
     * 从响应块中提取内容
     */
    private String extractContentFromChunk(String chunk) throws Exception {
        JsonNode rootNode = jsonMapper.readTree(chunk);
        return rootNode.path("choices")
                      .path(0)
                      .path("delta")
                      .path("content")
                      .asText("");
    }

    private String extractMessageContent(String chunk) throws Exception {
        JsonNode rootNode = jsonMapper.readTree(chunk);
        return rootNode.path("choices")
            .path(0)
            .path("message")
            .path("content")
            .asText("");
    }

    private void notifyCompletion(java.util.concurrent.atomic.AtomicBoolean flag,
                                  Runnable completionHandler) {
        if (completionHandler != null && flag.compareAndSet(false, true)) {
            completionHandler.run();
        }
    }
}
