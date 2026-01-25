package buaa.rag.service;

import buaa.rag.config.RagConfiguration;
import buaa.rag.dto.RetrievalMatch;
import buaa.rag.model.MessageFeedback;
import buaa.rag.repository.MessageFeedbackRepository;
import buaa.rag.repository.MessageSourceRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 反馈闭环服务
 */
@Service
public class FeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final MessageSourceRepository sourceRepository;
    private final RagConfiguration ragConfiguration;

    public FeedbackService(MessageFeedbackRepository feedbackRepository,
                           MessageSourceRepository sourceRepository,
                           RagConfiguration ragConfiguration) {
        this.feedbackRepository = feedbackRepository;
        this.sourceRepository = sourceRepository;
        this.ragConfiguration = ragConfiguration;
    }

    public void recordFeedback(Long messageId, String userId, int score, String comment) {
        MessageFeedback feedback = new MessageFeedback();
        feedback.setMessageId(messageId);
        feedback.setUserId(userId);
        feedback.setScore(score);
        feedback.setComment(comment);
        feedbackRepository.save(feedback);
    }

    public void applyFeedbackBoost(List<RetrievalMatch> matches) {
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
