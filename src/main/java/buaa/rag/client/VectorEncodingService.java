package buaa.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;

/**
 * 向量编码服务
 * 负责将文本转换为向量表示
 */
@Component
public class VectorEncodingService {

    private static final Logger log = LoggerFactory.getLogger(VectorEncodingService.class);
    
    @Value("${embedding.api.model}")
    private String encodingModel;
    
    @Value("${embedding.api.batch-size:100}")
    private int processingBatchSize;

    @Value("${embedding.api.dimension:2048}")
    private int vectorDimension;
    
    private final WebClient httpClient;
    private final ObjectMapper jsonParser;

    public VectorEncodingService(WebClient embeddingWebClient, ObjectMapper objectMapper) {
        this.httpClient = embeddingWebClient;
        this.jsonParser = objectMapper;
    }

    /**
     * 对文本列表进行向量编码
     * 
     * @param textList 待编码的文本列表
     * @return 对应的向量数组列表
     */
    public List<float[]> encode(List<String> textList) {
        try {
            log.info("启动向量编码任务，文本总数: {}", textList.size());
            
            List<float[]> allVectors = new ArrayList<>(textList.size());
            
            // 分批处理
            List<List<String>> batches = partitionIntoBatches(textList);
            
            for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                List<String> currentBatch = batches.get(batchIndex);
                log.debug("处理第 {}/{} 批，大小: {}", 
                         batchIndex + 1, batches.size(), currentBatch.size());
                
                String apiResponse = invokeEncodingApi(currentBatch);
                List<float[]> batchVectors = extractVectorsFromResponse(apiResponse);
                allVectors.addAll(batchVectors);
            }
            
            log.info("向量编码完成，共生成 {} 个向量", allVectors.size());
            return allVectors;
        } catch (Exception e) {
            log.error("向量编码失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量编码过程出错", e);
        }
    }

    /**
     * 将文本列表分批
     */
    private List<List<String>> partitionIntoBatches(List<String> textList) {
        List<List<String>> batches = new ArrayList<>();
        
        for (int i = 0; i < textList.size(); i += processingBatchSize) {
            int endIndex = Math.min(i + processingBatchSize, textList.size());
            batches.add(textList.subList(i, endIndex));
        }
        
        return batches;
    }

    /**
     * 调用编码API
     */
    private String invokeEncodingApi(List<String> batch) {
        Map<String, Object> requestBody = buildRequestBody(batch);

        return httpClient.post()
            .uri("/embeddings")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .retryWhen(createRetryPolicy())
            .block(Duration.ofSeconds(30));
    }

    /**
     * 构建请求体
     */
    private Map<String, Object> buildRequestBody(List<String> batch) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", encodingModel);
        body.put("input", batch);
        body.put("dimension", vectorDimension);
        body.put("encoding_format", "float");
        return body;
    }

    /**
     * 创建重试策略
     */
    private Retry createRetryPolicy() {
        return Retry.fixedDelay(3, Duration.ofSeconds(1))
            .filter(error -> error instanceof WebClientResponseException);
    }

    /**
     * 从API响应中提取向量
     */
    private List<float[]> extractVectorsFromResponse(String response) throws Exception {
        JsonNode responseJson = jsonParser.readTree(response);
        JsonNode dataArray = responseJson.get("data");
        
        if (dataArray == null || !dataArray.isArray()) {
            throw new RuntimeException("API响应格式异常: 缺少data数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        
        for (JsonNode item : dataArray) {
            JsonNode embeddingNode = item.get("embedding");
            if (embeddingNode != null && embeddingNode.isArray()) {
                float[] vector = parseVector(embeddingNode);
                vectors.add(vector);
            }
        }
        
        return vectors;
    }

    /**
     * 解析向量节点
     */
    private float[] parseVector(JsonNode embeddingNode) {
        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = (float) embeddingNode.get(i).asDouble();
        }
        return vector;
    }
}
