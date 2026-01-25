-- 步骤1: 创建新表
CREATE TABLE document_records (
    id                  BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    md5_hash            VARCHAR(32)      NOT NULL COMMENT '文档MD5哈希值',
    original_file_name  VARCHAR(255)     NOT NULL COMMENT '原始文件名',
    file_size_bytes     BIGINT           NOT NULL COMMENT '文件大小（字节）',
    processing_status   TINYINT          NOT NULL DEFAULT 0 COMMENT '处理状态：0-处理中，1-已完成',
    owner_id            VARCHAR(64)      NOT NULL DEFAULT 'anonymous' COMMENT '上传用户标识',
    visibility          VARCHAR(16)      NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性：PRIVATE/PUBLIC',
    doc_type            VARCHAR(64)      NULL COMMENT '文档类型：综测/请假/评奖等',
    department          VARCHAR(64)      NULL COMMENT '所属学院/部门',
    policy_year         VARCHAR(16)      NULL COMMENT '制度/政策年份',
    tags                VARCHAR(255)     NULL COMMENT '标签（逗号分隔）',
    uploaded_at         TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    processed_at        TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '处理完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_md5_hash (md5_hash) COMMENT 'MD5哈希唯一索引，防止重复上传'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档记录表';

CREATE TABLE text_segments (
    segment_id      BIGINT           NOT NULL AUTO_INCREMENT COMMENT '片段唯一标识',
    document_md5    VARCHAR(32)      NOT NULL COMMENT '关联文档的MD5值',
    fragment_index  INT              NOT NULL COMMENT '片段序号',
    text_data       TEXT             COMMENT '文本内容',
    encoding_model  VARCHAR(32)      COMMENT '编码模型版本',
    PRIMARY KEY (segment_id),
    INDEX idx_document_md5 (document_md5) COMMENT '文档MD5索引',
    INDEX idx_fragment (document_md5, fragment_index) COMMENT '文档和片段组合索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文本片段存储表';

CREATE TABLE conversation_messages (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    session_id  VARCHAR(64)  NOT NULL COMMENT '会话标识',
    user_id     VARCHAR(64)  NOT NULL COMMENT '用户标识',
    role        VARCHAR(16)  NOT NULL COMMENT '角色：user/assistant',
    content     LONGTEXT     NOT NULL COMMENT '消息内容',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_session (session_id) COMMENT '会话索引',
    INDEX idx_user (user_id) COMMENT '用户索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

CREATE TABLE conversation_message_sources (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    message_id       BIGINT       NOT NULL COMMENT '消息ID',
    document_md5     VARCHAR(32)  NOT NULL COMMENT '文档MD5',
    chunk_id         INT          NULL COMMENT '片段序号',
    relevance_score  DOUBLE       NULL COMMENT '相关度分数',
    source_file_name VARCHAR(255) NULL COMMENT '来源文件名',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_message (message_id) COMMENT '消息索引',
    INDEX idx_doc (document_md5) COMMENT '文档索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息来源表';

CREATE TABLE conversation_message_feedback (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    message_id  BIGINT       NOT NULL COMMENT '消息ID',
    user_id     VARCHAR(64)  NOT NULL COMMENT '用户标识',
    score       TINYINT      NOT NULL COMMENT '评分（1-5）',
    comment     VARCHAR(255) NULL COMMENT '反馈备注',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_feedback_message (message_id) COMMENT '消息索引',
    INDEX idx_feedback_user (user_id) COMMENT '用户索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息反馈表';

CREATE TABLE rag_evaluation_runs (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    started_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    finished_at    TIMESTAMP    NULL DEFAULT NULL COMMENT '结束时间',
    total_items    INT          NULL COMMENT '评测条目数',
    hit_rate       DOUBLE       NULL COMMENT '命中率',
    avg_similarity DOUBLE       NULL COMMENT '平均相似度',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测运行记录';

CREATE TABLE rag_evaluation_results (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    run_id          BIGINT       NOT NULL COMMENT '运行ID',
    item_id         VARCHAR(64)  NULL COMMENT '评测条目ID',
    question        LONGTEXT     NULL COMMENT '问题',
    expected_answer LONGTEXT     NULL COMMENT '期望答案',
    actual_answer   LONGTEXT     NULL COMMENT '模型答案',
    hit             TINYINT      NULL COMMENT '是否命中',
    similarity      DOUBLE       NULL COMMENT '相似度',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_eval_run (run_id) COMMENT '运行索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测结果明细';
