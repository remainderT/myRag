package org.buaa.rag.common.convention.errorcode;

/**
 * RAG业务错误码枚举
 *
 * 错误码规范：
 * - 0: 成功
 * - A0xxx: 客户端错误（参数校验、权限等）
 * - B0xxx: 服务端错误（业务逻辑、数据库等）
 * - C0xxx: 外部依赖错误（第三方服务）
 */
public enum RagErrorCode implements IErrorCode {

    // ==================== 通用错误 ====================
    /**
     * 操作成功
     */
    SUCCESS("0", "操作成功"),

    /**
     * 客户端请求错误
     */
    CLIENT_ERROR("A0001", "客户端请求错误"),

    /**
     * 服务端执行错误
     */
    SERVICE_ERROR("B0001", "服务端执行错误"),

    // ==================== 参数校验错误 (A01xx) ====================
    /**
     * 必填参数为空
     */
    PARAM_EMPTY("A0101", "必填参数为空"),

    /**
     * 参数格式错误
     */
    PARAM_INVALID("A0102", "参数格式错误"),

    /**
     * 消息内容不能为空
     */
    MESSAGE_EMPTY("A0103", "消息内容不能为空"),

    /**
     * 搜索关键词不能为空
     */
    QUERY_EMPTY("A0104", "搜索关键词不能为空"),

    /**
     * 消息ID不能为空
     */
    MESSAGE_ID_REQUIRED("A0105", "消息ID不能为空"),

    /**
     * 评分必须在1-5之间
     */
    SCORE_OUT_OF_RANGE("A0106", "评分必须在1-5之间"),

    // ==================== 文件相关错误 (A02xx) ====================
    /**
     * 不支持的文件格式
     */
    FILE_TYPE_NOT_SUPPORTED("A0201", "不支持的文件格式"),

    /**
     * 文件上传失败
     */
    FILE_UPLOAD_FAILED("A0202", "文件上传失败"),

    /**
     * 文件解析失败
     */
    FILE_PARSE_FAILED("A0203", "文件解析失败"),

    /**
     * 文件大小超出限制
     */
    FILE_SIZE_EXCEEDED("A0204", "文件大小超出限制"),

    /**
     * 文件已存在
     */
    FILE_ALREADY_EXISTS("A0205", "文件已存在"),

    // ==================== 权限相关错误 (A03xx) ====================
    /**
     * 无权访问该文件
     */
    FILE_ACCESS_DENIED("A0301", "无权访问该文件"),

    /**
     * 操作被禁止
     */
    OPERATION_FORBIDDEN("A0302", "操作被禁止"),

    // ==================== 业务逻辑错误 (A04xx) ====================
    /**
     * 文档不存在
     */
    DOCUMENT_NOT_FOUND("A0401", "文档不存在"),

    /**
     * 对话不存在
     */
    CONVERSATION_NOT_FOUND("A0402", "对话不存在"),

    /**
     * 检索失败
     */
    RETRIEVAL_FAILED("A0403", "检索失败"),

    // ==================== 服务端错误 (B0xxx) ====================
    /**
     * 对话服务异常
     */
    CHAT_SERVICE_ERROR("B0101", "对话服务异常"),

    /**
     * 搜索服务异常
     */
    SEARCH_SERVICE_ERROR("B0102", "搜索服务异常"),

    /**
     * 向量化服务异常
     */
    EMBEDDING_SERVICE_ERROR("B0103", "向量化服务异常"),

    /**
     * 大模型服务异常
     */
    LLM_SERVICE_ERROR("B0104", "大模型服务异常"),

    /**
     * 存储服务异常
     */
    STORAGE_SERVICE_ERROR("B0105", "存储服务异常"),

    /**
     * 数据库操作异常
     */
    DATABASE_ERROR("B0106", "数据库操作异常"),

    // ==================== 外部依赖错误 (C0xxx) ====================
    /**
     * Elasticsearch服务异常
     */
    ELASTICSEARCH_ERROR("C0101", "Elasticsearch服务异常"),

    /**
     * MinIO服务异常
     */
    MINIO_ERROR("C0102", "MinIO服务异常"),

    /**
     * DeepSeek API异常
     */
    DEEPSEEK_API_ERROR("C0103", "DeepSeek API异常"),

    /**
     * 向量化API异常
     */
    EMBEDDING_API_ERROR("C0104", "向量化API异常");

    private final String code;
    private final String message;

    RagErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return this.code;
    }

    @Override
    public String message() {
        return this.message;
    }
}
