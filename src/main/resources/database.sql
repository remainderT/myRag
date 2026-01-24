-- 步骤1: 创建新表
CREATE TABLE document_records (
    id                  BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    md5_hash            VARCHAR(32)      NOT NULL COMMENT '文档MD5哈希值',
    original_file_name  VARCHAR(255)     NOT NULL COMMENT '原始文件名',
    file_size_bytes     BIGINT           NOT NULL COMMENT '文件大小（字节）',
    processing_status   TINYINT          NOT NULL DEFAULT 0 COMMENT '处理状态：0-处理中，1-已完成',
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