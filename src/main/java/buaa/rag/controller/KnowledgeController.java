package buaa.rag.controller;

import buaa.rag.common.convention.errorcode.RagErrorCode;
import buaa.rag.common.convention.exception.ClientException;
import buaa.rag.common.convention.exception.ServiceException;
import buaa.rag.common.convention.result.Result;
import buaa.rag.common.convention.result.Results;
import buaa.rag.dto.ChatResponse;
import buaa.rag.dto.FeedbackRequest;
import buaa.rag.dto.MetadataFilter;
import buaa.rag.dto.RetrievalMatch;
import buaa.rag.model.Document;
import buaa.rag.repository.DocumentRepository;
import buaa.rag.service.ConversationManager;
import buaa.rag.service.DocumentIngestionService;
import buaa.rag.service.DocumentProcessor;
import buaa.rag.service.EmbeddingIndexer;
import buaa.rag.service.FeedbackService;
import buaa.rag.service.RerankerService;
import buaa.rag.service.SmartRetrieverService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * 知识库控制器
 * 提供对话、搜索、文件上传等接口
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    @Autowired
    private ConversationManager conversationManager;
    
    @Autowired
    private SmartRetrieverService retrieverService;

    @Autowired
    private RerankerService rerankerService;
    
    @Autowired
    private MinioClient storageClient;
    
    @Autowired
    private DocumentProcessor documentProcessor;
    
    @Autowired
    private EmbeddingIndexer embeddingIndexer;

    @Autowired
    private DocumentIngestionService documentIngestionService;
    
    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private FeedbackService feedbackService;

    @Value("${minio.bucketName}")
    private String storageBucket;

    // 支持的文档类型
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
        "pdf", "doc", "docx", "txt", "md", "html", "htm", 
        "xls", "xlsx", "ppt", "pptx", "rtf", "csv"
    );

    /**
     * 对话接口
     * POST /api/chat
     *
     * @param payload 包含message和userId的请求体
     * @return 统一响应格式
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> handleChatRequest(
            @RequestBody Map<String, String> payload) {
        String userMessage = payload.get("message");
        String userId = payload.getOrDefault("userId", "anonymous");

        if (isBlankString(userMessage)) {
            throw new ClientException(RagErrorCode.MESSAGE_EMPTY);
        }

        ChatResponse aiResponse = conversationManager.handleMessage(userId, userMessage);
        return Results.success(Map.of(
            "response", aiResponse.getResponse(),
            "sources", aiResponse.getSources()
        ));
    }

    /**
     * 对话流式接口（SSE）
     * GET /api/chat/stream?message=xxx&userId=xxx
     *
     * @param message 用户消息
     * @param userId 用户标识
     * @return SSE流
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleChatStream(
            @RequestParam String message,
            @RequestParam(defaultValue = "anonymous") String userId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (isBlankString(message)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                    .data(RagErrorCode.MESSAGE_EMPTY.message()));
            } catch (Exception ignored) {
            } finally {
                emitter.complete();
            }
            return emitter;
        }

        conversationManager.handleMessageStream(
            userId,
            message,
            chunk -> {
                try {
                    emitter.send(chunk);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            },
            error -> {
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("对话服务异常: " + error.getMessage()));
                } catch (Exception ignored) {
                } finally {
                    emitter.completeWithError(error);
                }
            },
            sources -> {
                try {
                    emitter.send(SseEmitter.event().name("sources").data(sources));
                } catch (Exception ignored) {
                }
            },
            messageId -> {
                try {
                    emitter.send(SseEmitter.event().name("messageId").data(messageId));
                } catch (Exception ignored) {
                }
            },
            () -> {
                try {
                    emitter.send(SseEmitter.event().name("done").data(""));
                } catch (Exception ignored) {
                } finally {
                    emitter.complete();
                }
            }
        );

        return emitter;
    }

    /**
     * 搜索接口
     * GET /api/search?query=xxx&topK=10
     *
     * @param query 搜索关键词
     * @param topK 返回结果数量
     * @return 搜索结果列表
     */
    @GetMapping("/search")
    public Result<List<RetrievalMatch>> handleSearchRequest(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String policyYear,
            @RequestParam(required = false) String tags) {
        if (isBlankString(query)) {
            throw new ClientException(RagErrorCode.QUERY_EMPTY);
        }

        MetadataFilter filter = buildMetadataFilter(department, docType, policyYear, tags);
        List<RetrievalMatch> results = retrieverService.retrieve(query, topK, userId, filter);
        results = rerankerService.rerank(query, results, topK);
        return Results.success(results);
    }

    /**
     * 反馈接口
     * POST /api/feedback
     */
    @PostMapping("/feedback")
    public Result<Map<String, Object>> handleFeedback(
            @RequestBody FeedbackRequest request) {
        if (request == null || request.getMessageId() == null) {
            throw new ClientException(RagErrorCode.MESSAGE_ID_REQUIRED);
        }

        int score = request.getScore() == null ? 0 : request.getScore();
        if (score < 1 || score > 5) {
            throw new ClientException(RagErrorCode.SCORE_OUT_OF_RANGE);
        }

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }

        feedbackService.recordFeedback(request.getMessageId(), userId, score, request.getComment());
        return Results.success(Map.of("messageId", request.getMessageId(), "score", score));
    }

    /**
     * 文件上传接口
     *
     * <p>接收文件并保存元数据，默认可见性为 PRIVATE。</p>
     *
     * @param uploadedFile 上传的文件
     * @param userId 上传用户标识，默认 anonymous
     * @param visibility 可见性，仅支持 PRIVATE/PUBLIC
     * @param department 所属部门，可选
     * @param docType 文档类型，可选
     * @param policyYear 政策年份，可选
     * @param tags 标签列表（逗号分隔），可选
     * @return 上传结果，包含文件 MD5、文件名等信息
     */
    @Transactional
    @PostMapping("/upload")
    public Result<Map<String, Object>> handleFileUpload(
            @RequestParam("file") MultipartFile uploadedFile,
            @RequestParam(defaultValue = "anonymous") String userId,
            @RequestParam(defaultValue = "PRIVATE") String visibility,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String policyYear,
            @RequestParam(required = false) String tags) {
        String originalFilename = uploadedFile.getOriginalFilename();

        try {
            // 步骤1: 验证文件类型
            if (!isValidFileType(originalFilename)) {
                throw new ClientException("不支持的文件格式，允许的格式: " + ALLOWED_FILE_TYPES,
                    RagErrorCode.FILE_TYPE_NOT_SUPPORTED);
            }

            // 步骤2: 计算文件哈希
            String fileHash = calculateMd5Hash(uploadedFile);

            // 步骤3: 检查文件是否已存在
            Optional<Document> existingRecord = findDocumentRecord(fileHash);
            if (existingRecord.isPresent()) {
                Document record = existingRecord.get();
                if (!Objects.equals(record.getOwnerId(), userId)) {
                    throw new ClientException("该文件已存在且归属其他用户",
                        RagErrorCode.FILE_ACCESS_DENIED);
                }

                String normalizedVisibility = normalizeVisibility(visibility);
                record.setVisibility(normalizedVisibility);
                applyMetadata(record, department, docType, policyYear, tags);
                documentRepository.save(record);

                return Results.success(Map.of(
                    "fileMd5", fileHash,
                    "fileName", record.getOriginalFileName(),
                    "message", "文件已存在，已更新权限"
                ));
            }

            // 步骤4: 存储到对象存储
            storeFileToMinio(uploadedFile, originalFilename, fileHash);

            // 步骤5: 保存元数据记录
            String normalizedVisibility = normalizeVisibility(visibility);
            saveDocumentRecord(
                fileHash,
                originalFilename,
                uploadedFile.getSize(),
                userId,
                normalizedVisibility,
                department,
                docType,
                policyYear,
                tags
            );

            // 步骤6: 异步解析文档并向量化
            documentIngestionService.ingestDocumentAsync(fileHash, originalFilename);

            return Results.success(Map.of(
                "fileMd5", fileHash,
                "fileName", originalFilename,
                "size", uploadedFile.getSize(),
                "message", "上传成功，后台解析中"
            ));

        } catch (ClientException e) {
            // 业务异常直接向上抛出
            throw e;
        } catch (Exception e) {
            // 其他异常包装为服务端异常
            throw new ServiceException("文件上传失败: " + e.getMessage(), e,
                RagErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 获取用户文档列表
     *
     * @param userId 用户标识
     * @return 文档列表
     */
    @GetMapping("/documents")
    public Result<List<Document>> listDocuments(@RequestParam String userId) {
        if (isBlankString(userId)) {
            throw new ClientException("用户标识不能为空", RagErrorCode.PARAM_EMPTY);
        }
        List<Document> documents = documentRepository.findByOwnerIdOrderByUploadedAtDesc(userId);
        return Results.success(documents);
    }

    /**
     * 删除指定文档
     *
     * @param md5Hash 文档MD5
     * @param userId 用户标识
     * @return 删除结果
     */
    @Transactional
    @DeleteMapping("/documents/{md5Hash}")
    public Result<Map<String, Object>> deleteDocument(
            @PathVariable String md5Hash,
            @RequestParam String userId) {
        if (isBlankString(userId) || isBlankString(md5Hash)) {
            throw new ClientException("参数不能为空", RagErrorCode.PARAM_EMPTY);
        }

        Optional<Document> recordOpt = documentRepository.findByMd5Hash(md5Hash);
        if (recordOpt.isEmpty()) {
            throw new ClientException("文档不存在", RagErrorCode.DOCUMENT_NOT_FOUND);
        }

        Document record = recordOpt.get();
        if (!Objects.equals(record.getOwnerId(), userId)) {
            throw new ClientException("无权限删除该文档", RagErrorCode.FILE_ACCESS_DENIED);
        }

        // 删除对象存储中的文件
        String objectPath = String.format("uploads/%s/%s", md5Hash, record.getOriginalFileName());
        try {
            storageClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageBucket)
                .object(objectPath)
                .build());
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e,
                RagErrorCode.STORAGE_SERVICE_ERROR);
        }

        // 删除索引与文本分块
        embeddingIndexer.removeDocumentIndex(md5Hash);
        documentProcessor.deleteSegments(md5Hash);
        documentRepository.deleteByMd5Hash(md5Hash);

        return Results.success(Map.of("fileMd5", md5Hash, "message", "删除成功"));
    }

    // ========== 辅助方法 ==========

    /**
     * 判断字符串是否为空
     */
    private boolean isBlankString(String str) {
        return str == null || str.isBlank();
    }

    /**
     * 验证文件类型
     */
    private boolean isValidFileType(String filename) {
        if (filename == null) return false;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) return false;
        String extension = filename.substring(dotIndex + 1).toLowerCase();
        return ALLOWED_FILE_TYPES.contains(extension);
    }

    /**
     * 计算文件MD5哈希
     */
    private String calculateMd5Hash(MultipartFile file) throws Exception {
        return DigestUtils.md5Hex(file.getInputStream());
    }

    /**
     * 检查文件是否已上传
     */
    private Optional<Document> findDocumentRecord(String md5Hash)
    {
        return documentRepository.findByMd5Hash(md5Hash);
    }

    /**
     * 存储文件到MinIO
     */
    private void storeFileToMinio(MultipartFile file, String filename, String fileHash) throws Exception {
        storageClient.putObject(PutObjectArgs.builder()
            .bucket("uploads")
            .object(String.format("uploads/%s/%s", fileHash, filename))
            .stream(file.getInputStream(), file.getSize(), -1)
            .contentType(file.getContentType())
            .build());
    }

    /**
     * 保存文档记录
     */
    private void saveDocumentRecord(String md5Hash,
                                    String filename,
                                    long fileSize,
                                    String ownerId,
                                    String visibility,
                                    String department,
                                    String docType,
                                    String policyYear,
                                    String tags) {
        Document record = new Document();
        record.setMd5Hash(md5Hash);
        record.setOriginalFileName(filename);
        record.setFileSizeBytes(fileSize);
        record.setProcessingStatus(0);
        record.setOwnerId(ownerId);
        record.setVisibility(visibility);
        applyMetadata(record, department, docType, policyYear, tags);
        record.setProcessedAt(null);
        documentRepository.save(record);
    }

    /**
     * 规范化可见性字段。
     *
     * <p>仅允许 PRIVATE/PUBLIC，其它值将回退为 PRIVATE。</p>
     *
     * @param visibility 原始可见性输入
     * @return 规范化后的可见性值
     */
    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null ? "PRIVATE" : visibility.trim().toUpperCase();
        if (!Set.of("PRIVATE", "PUBLIC").contains(normalized)) {
            return "PRIVATE";
        }
        return normalized;
    }

    private void applyMetadata(Document record,
                               String department,
                               String docType,
                               String policyYear,
                               String tags) {
        if (record == null) {
            return;
        }
        if (department != null && !department.isBlank()) {
            record.setDepartment(department.trim());
        }
        if (docType != null && !docType.isBlank()) {
            record.setDocType(docType.trim());
        }
        if (policyYear != null && !policyYear.isBlank()) {
            record.setPolicyYear(policyYear.trim());
        }
        String normalizedTags = normalizeTags(tags);
        if (normalizedTags != null) {
            record.setTags(normalizedTags);
        }
    }

    private MetadataFilter buildMetadataFilter(String department,
                                               String docType,
                                               String policyYear,
                                               String tags) {
        MetadataFilter filter = new MetadataFilter();
        if (department != null && !department.isBlank()) {
            filter.setDepartment(department.trim());
        }
        if (docType != null && !docType.isBlank()) {
            filter.setDocType(docType.trim());
        }
        if (policyYear != null && !policyYear.isBlank()) {
            filter.setPolicyYear(policyYear.trim());
        }
        List<String> tagList = parseTags(tags);
        if (!tagList.isEmpty()) {
            filter.setTags(tagList);
        }
        return filter;
    }

    private String normalizeTags(String tags) {
        List<String> tagList = parseTags(tags);
        if (tagList.isEmpty()) {
            return null;
        }
        return String.join(",", tagList);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = tags.split("[,，;；]");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed);
            }
        }
        return results.stream().distinct().toList();
    }
}
