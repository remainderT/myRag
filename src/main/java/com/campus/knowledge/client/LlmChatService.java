package com.campus.knowledge.client;

import com.campus.knowledge.config.LlmConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大语言模型聊天服务
 * 负责与LLM API进行流式交互
 * 
 * @author campus-team
 */
@Service
public class LlmChatService {

    private static final Logger log = LoggerFactory.getLogger(LlmChatService.class);
    
    private final WebClient httpClient;
    private final String apiToken;
    private final String modelIdentifier;
    private final LlmConfiguration llmConfig;
    private final ObjectMapper jsonMapper;
    
    public LlmChatService(@Value("${deepseek.api.url}") String baseUrl,
                         @Value("${deepseek.api.key}") String token,
                         @Value("${deepseek.api.model}") String model,
                         LlmConfiguration config) {
        this.httpClient = buildWebClient(baseUrl, token);
        this.apiToken = token;
        this.modelIdentifier = model;
        this.llmConfig = config;
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
                               Consumer<Throwable> errorHandler) {
        
        Map<String, Object> requestPayload = constructRequestPayload(
            userQuery, 
            referenceContext, 
            conversationHistory
        );
        
        log.info("发起流式请求，模型: {}", modelIdentifier);
        
        httpClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestPayload)
            .retrieve()
            .bodyToFlux(String.class)
            .subscribe(
                chunk -> parseAndHandleChunk(chunk, chunkHandler),
                errorHandler
            );
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
        LlmConfiguration.GenerationParams params = llmConfig.getGenerationParams();
        
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
        LlmConfiguration.PromptTemplate template = llmConfig.getPromptTemplate();

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
    private void parseAndHandleChunk(String chunk, Consumer<String> handler) {
        try {
            // 检查结束标记
            if ("[DONE]".equals(chunk.trim())) {
                log.debug("流式响应结束");
                return;
            }
            
            // 解析JSON获取内容
            String content = extractContentFromChunk(chunk);
            
            if (content != null && !content.isEmpty()) {
                handler.accept(content);
            }
        } catch (Exception e) {
            log.error("解析响应块失败: {}", e.getMessage(), e);
        }
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
}
