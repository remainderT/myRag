package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 文档记录实体
 * 存储上传文档的元数据信息
 */
@Data
@Entity
@Table(name = "document_records")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "md5_hash", length = 32, nullable = false)
    private String md5Hash;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "file_size_bytes")
    private long fileSizeBytes;

    @Column(name = "processing_status")
    private int processingStatus; // 0-处理中 1-已完成

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "visibility", length = 16)
    private String visibility;

    @Column(name = "doc_type", length = 64)
    private String docType;

    @Column(name = "department", length = 64)
    private String department;

    @Column(name = "policy_year", length = 16)
    private String policyYear;

    @Column(name = "tags", length = 255)
    private String tags;

    @CreationTimestamp
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @UpdateTimestamp
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /**
     * 判断文档是否已完成处理
     */
    public boolean isProcessed() {
        return processingStatus == 1;
    }

    /**
     * 标记为已完成
     */
    public void markAsCompleted() {
        this.processingStatus = 1;
    }

    /**
     * 标记为处理中
     */
    public void markAsProcessing() {
        this.processingStatus = 0;
    }
}
