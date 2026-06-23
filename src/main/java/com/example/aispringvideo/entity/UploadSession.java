package com.example.aispringvideo.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片上传会话（非数据库实体，仅内存中维护）
 */
@Getter
@Setter
@ToString
public class UploadSession {

    private String uploadId;
    private Long userId;
    private String filename;
    private long fileSize;
    private int totalChunks;
    private int chunkSize;
    /** 已接收的分片索引 */
    private final Set<Integer> receivedChunks = ConcurrentHashMap.newKeySet();
    /** 临时目录路径 */
    private String tempDir;
    /** 会话状态: UPLOADING / COMPLETED / CANCELLED */
    private String status;
    private LocalDateTime createdAt;

    public UploadSession(String uploadId, Long userId, String filename,
                         long fileSize, int totalChunks, int chunkSize, String tempDir) {
        this.uploadId = uploadId;
        this.userId = userId;
        this.filename = filename;
        this.fileSize = fileSize;
        this.totalChunks = totalChunks;
        this.chunkSize = chunkSize;
        this.tempDir = tempDir;
        this.status = "UPLOADING";
        this.createdAt = LocalDateTime.now();
    }

    public int getReceivedCount() {
        return receivedChunks.size();
    }

    public int getProgressPercent() {
        if (totalChunks == 0) return 0;
        return (int) ((long) receivedChunks.size() * 100 / totalChunks);
    }
}
