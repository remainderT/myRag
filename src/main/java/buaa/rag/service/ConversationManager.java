package buaa.rag.service;

import buaa.rag.dto.ChatResponse;
import buaa.rag.dto.CragDecision;
import buaa.rag.dto.MetadataFilter;
import buaa.rag.dto.QueryPlan;
import buaa.rag.dto.RetrievalMatch;
import buaa.rag.client.LlmChatService;
import buaa.rag.config.RagConfiguration;
import buaa.rag.model.Message;
import buaa.rag.model.MessageSource;
import buaa.rag.repository.MessageRepository;
import buaa.rag.repository.MessageSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话管理器
 * 负责管理用户会话和协调检索、生成流程
 */
@Service
public class ConversationManager {
    
    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final int MAX_HISTORY_SIZE = 20;
    private static final int MAX_REFERENCE_LENGTH = 300;
    private static final int DEFAULT_RETRIEVAL_K = 5;
    private static final int MAX_RETRIEVAL_K = 10;
    private static final double MIN_ACCEPTABLE_SCORE = 0.25;
    
    // 用户ID到会话ID的映射
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();
    // 会话历史存储
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();

    private final SmartRetrieverService retrieverService;
    private final LlmChatService llmService;
    private final MessageRepository messageRepository;
    private final MessageSourceRepository sourceRepository;
    private final QueryRefiner queryRefiner;
    private final RagConfiguration ragConfiguration;
    private final RerankerService rerankerService;
    private final QueryRoutingService routingService;
    private final CragService cragService;

    public ConversationManager(SmartRetrieverService retrieverService,
                               LlmChatService llmService,
                               MessageRepository messageRepository,
                               MessageSourceRepository sourceRepository,
                               QueryRefiner queryRefiner,
                               RagConfiguration ragConfiguration,
                               RerankerService rerankerService,
                               QueryRoutingService routingService,
                               CragService cragService) {
        this.retrieverService = retrieverService;
        this.llmService = llmService;
        this.messageRepository = messageRepository;
        this.sourceRepository = sourceRepository;
        this.queryRefiner = queryRefiner;
        this.ragConfiguration = ragConfiguration;
        this.rerankerService = rerankerService;
        this.routingService = routingService;
        this.cragService = cragService;
    }

    /**
     * 处理用户消息并返回AI响应
     * 
     * @param userId 用户标识
     * @param userMessage 用户消息
     * @return AI完整响应
     */
    public ChatResponse handleMessage(String userId, String userMessage) {
        log.info("处理用户消息 - 用户: {}", userId);
        
        try {
            // 步骤1: 获取或创建会话
            String sessionId = obtainOrCreateSession(userId);
            log.info("会话ID: {}, 用户: {}", sessionId, userId);
            
            // 步骤2: 加载对话历史
            List<Map<String, String>> conversationHistory = loadConversationHistory(sessionId);
            log.debug("历史记录数: {}", conversationHistory.size());
            
            // 步骤3: 执行知识检索
            int retrievalK = determineRetrievalK(userMessage);
            MetadataFilter filter = routingService.resolveFilter(userMessage);
            List<RetrievalMatch> retrievalResults = retrieveMatches(userId, userMessage, retrievalK, filter);
            log.debug("检索到 {} 条相关结果", retrievalResults.size());

            CragDecision decision = cragService.evaluate(userMessage, retrievalResults);
            if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
                String response = decision.getMessage();
                Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                return new ChatResponse(response, retrievalResults, messageId);
            }

            if (decision.getAction() == CragDecision.Action.REFINE) {
                List<RetrievalMatch> fallback = runFallbackRetrieval(
                    userId,
                    userMessage,
                    retrievalK,
                    filter
                );
                if (!fallback.isEmpty()) {
                    retrievalResults = fallback;
                } else {
                    String response = cragService.noResultMessage();
                    Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                    return new ChatResponse(response, retrievalResults, messageId);
                }
            }

            // 步骤4: 构造参考上下文
            String referenceContext = constructReferenceContext(retrievalResults);
            
            // 步骤5: 调用LLM生成响应
            StringBuilder responseBuilder = new StringBuilder();
            CountDownLatch completionLatch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            
            log.info("调用LLM生成响应");

            llmService.streamResponse(
                userMessage, 
                referenceContext, 
                conversationHistory, 
                responseBuilder::append,
                error -> {
                    log.error("LLM服务错误: {}", error.getMessage(), error);
                    errorRef.set(error);
                    completionLatch.countDown();
                },
                completionLatch::countDown
            );
            
            if (!completionLatch.await(120, TimeUnit.SECONDS)) {
                throw new RuntimeException("AI响应超时，请稍后重试");
            }
            
            if (errorRef.get() != null) {
                throw new RuntimeException("AI服务异常: " + errorRef.get().getMessage(), errorRef.get());
            }
            
            String finalResponse = responseBuilder.toString();
            log.info("LLM响应完成，长度: {}", finalResponse.length());
            
            // 步骤6: 更新对话历史
            Long messageId = appendToHistory(sessionId, userId, userMessage, finalResponse, retrievalResults);
            
            log.info("消息处理完成 - 用户: {}", userId);
            return new ChatResponse(finalResponse, retrievalResults, messageId);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("响应生成被中断", e);
        } catch (Exception e) {
            log.error("消息处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话处理异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理用户消息并进行流式输出
     *
     * @param userId 用户标识
     * @param userMessage 用户消息
     * @param chunkHandler 流式片段处理器
     * @param errorHandler 错误处理器
     * @param completionHandler 完成回调
     */
    public void handleMessageStream(String userId,
                                    String userMessage,
                                    java.util.function.Consumer<String> chunkHandler,
                                    java.util.function.Consumer<Throwable> errorHandler,
                                    java.util.function.Consumer<List<RetrievalMatch>> sourcesHandler,
                                    java.util.function.Consumer<Long> messageIdHandler,
                                    Runnable completionHandler) {
        try {
            String sessionId = obtainOrCreateSession(userId);
            List<Map<String, String>> conversationHistory = loadConversationHistory(sessionId);
            int retrievalK = determineRetrievalK(userMessage);
            MetadataFilter filter = routingService.resolveFilter(userMessage);
            List<RetrievalMatch> retrievalResults = retrieveMatches(userId, userMessage, retrievalK, filter);
            String referenceContext = constructReferenceContext(retrievalResults);
            StringBuilder responseBuilder = new StringBuilder();

            CragDecision decision = cragService.evaluate(userMessage, retrievalResults);
            if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
                String response = decision.getMessage();
                responseBuilder.append(response);
                if (chunkHandler != null) {
                    chunkHandler.accept(response);
                }
                Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                if (sourcesHandler != null) {
                    sourcesHandler.accept(retrievalResults);
                }
                if (messageIdHandler != null) {
                    messageIdHandler.accept(messageId);
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            if (decision.getAction() == CragDecision.Action.REFINE) {
                List<RetrievalMatch> fallback = runFallbackRetrieval(
                    userId,
                    userMessage,
                    retrievalK,
                    filter
                );
                if (!fallback.isEmpty()) {
                    retrievalResults = fallback;
                    referenceContext = constructReferenceContext(retrievalResults);
                } else {
                    String response = cragService.noResultMessage();
                    responseBuilder.append(response);
                    if (chunkHandler != null) {
                        chunkHandler.accept(response);
                    }
                    Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                    if (sourcesHandler != null) {
                        sourcesHandler.accept(retrievalResults);
                    }
                    if (messageIdHandler != null) {
                        messageIdHandler.accept(messageId);
                    }
                    if (completionHandler != null) {
                        completionHandler.run();
                    }
                    return;
                }
            }

            List<RetrievalMatch> finalResults = retrievalResults;

            llmService.streamResponse(
                userMessage, 
                referenceContext, 
                conversationHistory, 
                chunk -> {
                    responseBuilder.append(chunk);
                    if (chunkHandler != null) {
                        chunkHandler.accept(chunk);
                    }
                },
                error -> {
                    if (errorHandler != null) {
                        errorHandler.accept(error);
                    }
                },
                () -> {
                    String finalResponse = responseBuilder.toString();
                    Long messageId = appendToHistory(sessionId, userId, userMessage, finalResponse, finalResults);
                    if (sourcesHandler != null) {
                        sourcesHandler.accept(finalResults);
                    }
                    if (messageIdHandler != null) {
                        messageIdHandler.accept(messageId);
                    }
                    if (completionHandler != null) {
                        completionHandler.run();
                    }
                }
            );
        } catch (Exception e) {
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
        }
    }

    /**
     * 获取或创建会话
     */
    private String obtainOrCreateSession(String userId) {
        return userSessionMap.computeIfAbsent(userId, key -> {
            String existingSession = loadLatestSessionId(userId);
            if (existingSession != null) {
                log.info("复用历史会话 - 用户: {}, 会话ID: {}", userId, existingSession);
                return existingSession;
            }

            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", userId, newSessionId);
            return newSessionId;
        });
    }

    /**
     * 加载对话历史
     */
    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        List<Map<String, String>> history = loadHistoryFromDatabase(sessionId);
        if (history != null) {
            sessionHistoryMap.put(sessionId, history);
            return history;
        }
        return sessionHistoryMap.getOrDefault(sessionId, new ArrayList<>());
    }

    /**
     * 追加到历史记录
     */
    private Long appendToHistory(String sessionId,
                                 String userId,
                                 String userMessage,
                                 String aiResponse,
                                 List<RetrievalMatch> sources) {
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
        persistMessage(sessionId, userId, "user", userMessage);
        
        // 添加AI响应
        Map<String, String> assistantEntry = new HashMap<>();
        assistantEntry.put("role", "assistant");
        assistantEntry.put("content", aiResponse);
        assistantEntry.put("timestamp", timestamp);
        history.add(assistantEntry);
        Long assistantMessageId = persistMessage(sessionId, userId, "assistant", aiResponse);
        persistSources(assistantMessageId, sources);
        
        // 限制历史记录长度
        if (history.size() > MAX_HISTORY_SIZE) {
            List<Map<String, String>> trimmedHistory = new ArrayList<>(
                history.subList(history.size() - MAX_HISTORY_SIZE, history.size())
            );
            sessionHistoryMap.put(sessionId, trimmedHistory);
        }
        
        log.debug("更新会话历史 - 会话: {}, 总消息数: {}", sessionId, history.size());
        return assistantMessageId;
    }

    /**
     * 获取当前时间戳
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private List<RetrievalMatch> retrieveMatches(String userId,
                                                 String message,
                                                 int topK,
                                                 MetadataFilter filter) {
        QueryPlan plan = queryRefiner.createPlan(message);
        List<List<RetrievalMatch>> resultSets = new ArrayList<>();

        resultSets.add(retrieveWithFallback(userId, message, topK, filter));

        int remainingQueries = ragConfiguration.getFusion().getMaxQueries() - 1;
        if (ragConfiguration.getHyde().isEnabled()) {
            remainingQueries -= 1;
        }

        if (ragConfiguration.getRewrite().isEnabled() && remainingQueries > 0) {
            List<String> rewrites = plan.getRewrittenQueries();
            if (rewrites != null && !rewrites.isEmpty()) {
                int limit = Math.min(remainingQueries, rewrites.size());
                for (int i = 0; i < limit; i++) {
                    resultSets.add(retrieveWithFallback(userId, rewrites.get(i), topK, filter));
                }
            }
        }

        if (ragConfiguration.getHyde().isEnabled()) {
            String hydeAnswer = plan.getHydeAnswer();
            if (hydeAnswer != null && !hydeAnswer.isBlank()) {
                resultSets.add(retrieverService.retrieveVectorOnly(hydeAnswer, topK, userId, filter));
            }
        }

        if (!ragConfiguration.getFusion().isEnabled() || resultSets.size() == 1) {
            return rerankerService.rerank(message, resultSets.get(0), topK);
        }

        List<RetrievalMatch> fused = fuseByRrf(resultSets, topK, ragConfiguration.getFusion().getRrfK());
        return rerankerService.rerank(message, fused, topK);
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

    private List<RetrievalMatch> retrieveWithFallback(String userId,
                                                      String message,
                                                      int topK,
                                                      MetadataFilter filter) {
        List<RetrievalMatch> results = retrieverService.retrieve(message, topK, userId, filter);
        if (!isLowQualityForFallback(results)) {
            return results;
        }

        String refinedQuery = normalizeQuery(message);
        if (!refinedQuery.equals(message)) {
            List<RetrievalMatch> refined = retrieverService.retrieve(
                refinedQuery,
                Math.min(topK * 2, MAX_RETRIEVAL_K),
                userId,
                filter
            );
            if (!isLowQualityForFallback(refined)) {
                return refined;
            }
        }

        return results;
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

    private boolean isLowQualityForFallback(List<RetrievalMatch> results) {
        if (results == null || results.isEmpty()) {
            return true;
        }
        Double topScore = results.get(0).getRelevanceScore();
        return topScore == null || topScore < MIN_ACCEPTABLE_SCORE;
    }

    private String normalizeQuery(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("[\\t\\n\\r]", " ")
            .replaceAll("[，。！？；、]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private List<RetrievalMatch> runFallbackRetrieval(String userId,
                                                      String message,
                                                      int topK,
                                                      MetadataFilter filter) {
        RagConfiguration.Crag config = ragConfiguration.getCrag();
        int multiplier = config != null ? config.getFallbackMultiplier() : 2;
        int fallbackK = Math.min(topK * Math.max(1, multiplier), MAX_RETRIEVAL_K);
        List<RetrievalMatch> fallback = retrieverService.retrieveTextOnly(
            message,
            fallbackK,
            userId,
            filter
        );
        return rerankerService.rerank(message, fallback, topK);
    }

    private String loadLatestSessionId(String userId) {
        try {
            return messageRepository.findTop1ByUserIdOrderByCreatedAtDesc(userId)
                .map(Message::getSessionId)
                .orElse(null);
        } catch (Exception e) {
            log.debug("读取历史会话失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, String>> loadHistoryFromDatabase(String sessionId) {
        try {
            List<Message> messages = messageRepository
                .findTop20BySessionIdOrderByCreatedAtAsc(sessionId);
            if (messages == null || messages.isEmpty()) {
                return null;
            }
            List<Map<String, String>> history = new ArrayList<>();
            for (Message message : messages) {
                Map<String, String> entry = new HashMap<>();
                entry.put("role", message.getRole());
                entry.put("content", message.getContent());
                entry.put("timestamp", formatTimestamp(message.getCreatedAt()));
                history.add(entry);
            }
            return history;
        } catch (Exception e) {
            log.debug("加载对话历史失败: {}", e.getMessage());
            return null;
        }
    }

    private Long persistMessage(String sessionId, String userId, String role, String content) {
        try {
            Message message = new Message();
            message.setSessionId(sessionId);
            message.setUserId(userId);
            message.setRole(role);
            message.setContent(content);
            Message saved = messageRepository.save(message);
            return saved.getId();
        } catch (Exception e) {
            log.debug("持久化对话失败: {}", e.getMessage());
            return null;
        }
    }

    private void persistSources(Long messageId, List<RetrievalMatch> sources) {
        if (messageId == null || sources == null || sources.isEmpty()) {
            return;
        }
        try {
            for (RetrievalMatch match : sources) {
                MessageSource source = new MessageSource();
                source.setMessageId(messageId);
                source.setDocumentMd5(match.getFileMd5());
                source.setChunkId(match.getChunkId());
                source.setRelevanceScore(match.getRelevanceScore());
                source.setSourceFileName(match.getSourceFileName());
                sourceRepository.save(source);
            }
        } catch (Exception e) {
            log.debug("持久化来源失败: {}", e.getMessage());
        }
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return getCurrentTimestamp();
        }
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
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

}
