package com.example.aispringvideo.controller;

import com.example.aispringvideo.entity.UploadSession;
import com.example.aispringvideo.entity.VideoTask;
import com.example.aispringvideo.service.ChunkUploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tasks/upload")
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;
    private final HttpServletRequest request;

    public ChunkUploadController(ChunkUploadService chunkUploadService, HttpServletRequest request) {
        this.chunkUploadService = chunkUploadService;
        this.request = request;
    }

    /**
     * 初始化分片上传
     */
    @PostMapping("/init")
    public ResponseEntity<?> initUpload(@RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String filename = (String) body.get("filename");
        long fileSize = ((Number) body.get("fileSize")).longValue();
        int totalChunks = ((Number) body.get("totalChunks")).intValue();
        int chunkSize = ((Number) body.get("chunkSize")).intValue();

        if (filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件名不能为空"));
        }

        UploadSession session = chunkUploadService.initUpload(
                userId, filename, fileSize, totalChunks, chunkSize);

        log.info("初始化分片上传: uploadId={}, filename={}, size={}, chunks={}",
                session.getUploadId(), filename, fileSize, totalChunks);

        return ResponseEntity.ok(Map.of(
                "uploadId", session.getUploadId()
        ));
    }

    /**
     * 上传单个分片
     */
    @PostMapping("/{uploadId}/chunk")
    public ResponseEntity<?> uploadChunk(
            @PathVariable String uploadId,
            @RequestParam("chunk") int chunkIndex,
            @RequestParam("file") MultipartFile file) {

        Long userId = getCurrentUserId();
        UploadSession session = chunkUploadService.getSession(uploadId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("error", "上传会话不存在"));
        }
        if (!session.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权操作此上传"));
        }

        try {
            byte[] data = file.getBytes();
            chunkUploadService.saveChunk(uploadId, chunkIndex, data);
            return ResponseEntity.ok(Map.of("chunkIndex", chunkIndex));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("保存分片失败: uploadId={}, chunk={}", uploadId, chunkIndex, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "分片保存失败"));
        }
    }

    /**
     * 查询上传状态（已接收的分片列表）
     */
    @GetMapping("/{uploadId}")
    public ResponseEntity<?> getUploadStatus(@PathVariable String uploadId) {
        UploadSession session = chunkUploadService.getSession(uploadId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("error", "上传会话不存在"));
        }

        return ResponseEntity.ok(Map.of(
                "uploadId", uploadId,
                "receivedChunks", chunkUploadService.getReceivedChunks(uploadId),
                "totalChunks", session.getTotalChunks(),
                "progress", session.getProgressPercent(),
                "status", session.getStatus()
        ));
    }

    /**
     * 完成上传（合并分片并创建任务）
     */
    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<?> completeUpload(
            @PathVariable String uploadId,
            @RequestParam("filename") String filename) {

        Long userId = getCurrentUserId();
        UploadSession session = chunkUploadService.getSession(uploadId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("error", "上传会话不存在"));
        }
        if (!session.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权操作此上传"));
        }

        try {
            VideoTask task = chunkUploadService.completeUpload(uploadId, filename, userId);

            if (task.getStatus().equals("SUCCESS")) {
                // 命中缓存
                return ResponseEntity.ok(Map.of(
                        "cached", true,
                        "taskId", task.getId(),
                        "status", task.getStatus(),
                        "summary", task.getSummary(),
                        "duration", task.getDuration()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "cached", false,
                        "taskId", task.getId(),
                        "status", task.getStatus()
                ));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("合并分片失败: uploadId={}", uploadId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "合并文件失败"));
        }
    }

    /**
     * 取消上传
     */
    @DeleteMapping("/{uploadId}")
    public ResponseEntity<?> cancelUpload(@PathVariable String uploadId) {
        chunkUploadService.cancelUpload(uploadId);
        log.info("取消上传: uploadId={}", uploadId);
        return ResponseEntity.ok(Map.of("cancelled", true));
    }

    private Long getCurrentUserId() {
        return (Long) request.getAttribute("userId");
    }
}
