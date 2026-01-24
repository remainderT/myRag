package com.campus.knowledge.service;

import com.campus.knowledge.dto.RetrievalMatch;
import com.campus.knowledge.client.LlmChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话管理器
 * 负责管理用户会话和协调检索、生成流程
 * 
 * @author campus-team
 */
@Service
public class ConversationManager {
    
    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final int MAX_HISTORY_SIZE = 20;
    private static final int MAX_REFERENCE_LENGTH = 300;
    private static final int DEFAULT_RETRIEVAL_K = 5;
    
    // 用户ID到会话ID的映射
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();
    // 会话历史存储
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();

    private final SmartRetrieverService retrieverService;
    private final LlmChatService llmService;

    public ConversationManager(SmartRetrieverService retrieverService,
                               LlmChatService llmService) {
        this.retrieverService = retrieverService;
        this.llmService = llmService;
    }

    /**
     * 处理用户消息并返回AI响应
     * 
     * @param userId 用户标识
     * @param userMessage 用户消息
     * @return AI完整响应
     */
    public String handleMessage(String userId, String userMessage) {
        log.info("处理用户消息 - 用户: {}", userId);
        
        try {
            // 步骤1: 获取或创建会话
            String sessionId = obtainOrCreateSession(userId);
            log.info("会话ID: {}, 用户: {}", sessionId, userId);
            
            // 步骤2: 加载对话历史
            List<Map<String, String>> conversationHistory = loadConversationHistory(sessionId);
            log.debug("历史记录数: {}", conversationHistory.size());
            
            // 步骤3: 执行知识检索
            List<RetrievalMatch> retrievalResults = retrieverService.retrieve(
                userMessage, 
                DEFAULT_RETRIEVAL_K
            );
            log.debug("检索到 {} 条相关结果", retrievalResults.size());
            
            // 步骤4: 构造参考上下文
            String referenceContext = constructReferenceContext(retrievalResults);
            
            // 步骤5: 调用LLM生成响应
            StringBuilder responseBuilder = new StringBuilder();
            
            log.info("调用LLM生成响应");
            try {
                llmService.streamResponse(
                    userMessage, 
                    referenceContext, 
                    conversationHistory, 
                    chunk -> responseBuilder.append(chunk),
                    error -> {
                        log.error("LLM服务错误: {}", error.getMessage(), error);
                        throw new RuntimeException("AI服务异常: " + error.getMessage(), error);
                    }
                );
                
                // 等待流式响应完成
                waitForResponseCompletion(responseBuilder);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("响应生成被中断", e);
            }
            
            String finalResponse = responseBuilder.toString();
            log.info("LLM响应完成，长度: {}", finalResponse.length());
            
            // 步骤6: 更新对话历史
            appendToHistory(sessionId, userMessage, finalResponse);
            
            log.info("消息处理完成 - 用户: {}", userId);
            return finalResponse;
            
        } catch (Exception e) {
            log.error("消息处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话处理异常: " + e.getMessage(), e);
        }
    }

    /**
     * 获取或创建会话
     */
    private String obtainOrCreateSession(String userId) {
        return userSessionMap.computeIfAbsent(userId, key -> {
            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", userId, newSessionId);
            return newSessionId;
        });
    }

    /**
     * 加载对话历史
     */
    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        return sessionHistoryMap.getOrDefault(sessionId, new ArrayList<>());
    }

    /**
     * 追加到历史记录
     */
    private void appendToHistory(String sessionId, String userMessage, String aiResponse) {
        List<Map<String, String>> history = sessionHistoryMap.computeIfAbsent(
            sessionId, 
            key -> new ArrayList<>()
        );
        
        String timestamp = getCurrentTimestamp();
        
        // 添加用户消息
        Map<String, String> userEntry = new HashMap<>();
        userEntry.put("role", "user");
        userEntry.put("content", userMessage);
        userEntry.put("timestamp", timestamp);
        history.add(userEntry);
        
        // 添加AI响应
        Map<String, String> assistantEntry = new HashMap<>();
        assistantEntry.put("role", "assistant");
        assistantEntry.put("content", aiResponse);
        assistantEntry.put("timestamp", timestamp);
        history.add(assistantEntry);
        
        // 限制历史记录长度
        if (history.size() > MAX_HISTORY_SIZE) {
            List<Map<String, String>> trimmedHistory = new ArrayList<>(
                history.subList(history.size() - MAX_HISTORY_SIZE, history.size())
            );
            sessionHistoryMap.put(sessionId, trimmedHistory);
        }
        
        log.debug("更新会话历史 - 会话: {}, 总消息数: {}", sessionId, history.size());
    }

    /**
     * 获取当前时间戳
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    /**
     * 构造参考上下文
     */
    private String constructReferenceContext(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }

        StringBuilder contextBuilder = new StringBuilder();
        
        for (int i = 0; i < matches.size(); i++) {
            RetrievalMatch match = matches.get(i);
            String textSnippet = truncateText(match.getTextContent(), MAX_REFERENCE_LENGTH);
            String sourceLabel = getSourceLabel(match);
            
            contextBuilder.append(String.format(
                "[%d] (%s) %s\n", 
                i + 1, 
                sourceLabel, 
                textSnippet
            ));
        }
        
        return contextBuilder.toString();
    }

    /**
     * 截断文本
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }

    /**
     * 获取来源标签
     */
    private String getSourceLabel(RetrievalMatch match) {
        return match.getSourceFileName() != null ? 
               match.getSourceFileName() : 
               "未知来源";
    }

    /**
     * 等待响应完成
     */
    private void waitForResponseCompletion(StringBuilder responseBuilder) 
            throws InterruptedException {
        // 初始等待
        Thread.sleep(2000);
        
        // 持续检查直到响应稳定（2秒内无变化）
        int previousLength = responseBuilder.length();
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(1000);
            int currentLength = responseBuilder.length();
            if (currentLength == previousLength) {
                // 响应已稳定
                break;
            }
            previousLength = currentLength;
        }
    }
}
