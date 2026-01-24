package com.campus.knowledge.controller;

import com.campus.knowledge.dto.RetrievalMatch;
import com.campus.knowledge.service.*;
import com.campus.knowledge.model.DocumentRecord;
import com.campus.knowledge.repository.DocumentRecordRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 知识库控制器
 * 提供对话、搜索、文件上传等接口
 * 
 * @author campus-team
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    @Autowired
    private ConversationManager conversationManager;
    
    @Autowired
    private SmartRetrieverService retrieverService;
    
    @Autowired
    private MinioClient storageClient;
    
    @Autowired
    private DocumentProcessor documentProcessor;
    
    @Autowired
    private EmbeddingIndexer embeddingIndexer;
    
    @Autowired
    private DocumentRecordRepository documentRepository;

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
    public ResponseEntity<Map<String, Object>> handleChatRequest(
            @RequestBody Map<String, String> payload) {
        try {
            String userMessage = payload.get("message");
            String userId = payload.getOrDefault("userId", "anonymous");
            
            if (isBlankString(userMessage)) {
                return buildErrorResponse(400, "消息内容不能为空");
            }
            
            String aiResponse = conversationManager.handleMessage(userId, userMessage);
            return buildSuccessResponse(Map.of("response", aiResponse));
            
        } catch (Exception e) {
            e.printStackTrace();
            return buildErrorResponse(500, "对话服务异常: " + e.getMessage());
        }
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
    public ResponseEntity<Map<String, Object>> handleSearchRequest(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK) {
        try {
            if (isBlankString(query)) {
                return buildErrorResponse(400, "搜索关键词不能为空");
            }
            
            List<RetrievalMatch> results = retrieverService.retrieve(query, topK);
            return buildSuccessResponse(results);
            
        } catch (Exception e) {
            e.printStackTrace();
            return buildErrorResponse(500, "搜索服务异常: " + e.getMessage());
        }
    }

    /**
     * 文件上传接口
     * POST /api/upload
     * 
     * @param uploadedFile 上传的文件
     * @return 上传结果
     */
    @Transactional
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> handleFileUpload(
            @RequestParam("file") MultipartFile uploadedFile) {
        String originalFilename = uploadedFile.getOriginalFilename();
        
        try {
            // 步骤1: 验证文件类型
            if (!isValidFileType(originalFilename)) {
                return buildErrorResponse(400, 
                    "不支持的文件格式，允许的格式: " + ALLOWED_FILE_TYPES);
            }
            
            // 步骤2: 计算文件哈希
            String fileHash = calculateMd5Hash(uploadedFile);
            
            // 步骤3: 检查文件是否已存在
            if (isFileAlreadyUploaded(fileHash)) {
                return buildSuccessResponse(Map.of(
                    "fileMd5", fileHash, 
                    "message", "文件已存在，无需重复上传"
                ));
            }
            
            // 步骤4: 存储到对象存储
            storeFileToMinio(uploadedFile, originalFilename);
            
            // 步骤5: 保存元数据记录
            saveDocumentRecord(fileHash, originalFilename, uploadedFile.getSize());
            
            // 步骤6: 解析文档并向量化
            try (var inputStream = new BufferedInputStream(uploadedFile.getInputStream())) {
                documentProcessor.processAndStore(fileHash, inputStream);
            }
            embeddingIndexer.indexDocument(fileHash);
            
            return buildSuccessResponse(Map.of(
                "fileMd5", fileHash,
                "fileName", originalFilename,
                "size", uploadedFile.getSize()
            ));
            
        } catch (Exception e) {
            e.printStackTrace();
            return buildErrorResponse(500, "文件上传失败: " + e.getMessage());
        }
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
    private boolean isFileAlreadyUploaded(String md5Hash) {
        return documentRepository.findByMd5Hash(md5Hash).isPresent();
    }

    /**
     * 存储文件到MinIO
     */
    private void storeFileToMinio(MultipartFile file, String filename) throws Exception {
        storageClient.putObject(PutObjectArgs.builder()
            .bucket("uploads")
            .object("uploads/" + filename)
            .stream(file.getInputStream(), file.getSize(), -1)
            .contentType(file.getContentType())
            .build());
    }

    /**
     * 保存文档记录
     */
    private void saveDocumentRecord(String md5Hash, String filename, long fileSize) {
        DocumentRecord record = new DocumentRecord();
        record.setMd5Hash(md5Hash);
        record.setOriginalFileName(filename);
        record.setFileSizeBytes(fileSize);
        record.setProcessingStatus(1);
        record.setProcessedAt(LocalDateTime.now());
        documentRepository.save(record);
    }

    /**
     * 构建成功响应
     */
    private ResponseEntity<Map<String, Object>> buildSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 构建错误响应
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(int statusCode, String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", statusCode);
        response.put("message", errorMessage);
        return ResponseEntity.status(statusCode).body(response);
    }
}
