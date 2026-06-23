package com.example.aispringvideo.service;

import com.example.aispringvideo.entity.UploadSession;
import com.example.aispringvideo.entity.VideoTask;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChunkUploadService {

    private static final String KEY_PREFIX_SESSION = "upload:session:";
    private static final String KEY_PREFIX_CHUNKS = "upload:chunks:";
    private static final long SESSION_TTL_SECONDS = 86400; // 24 小时

    @Value("${video.upload.dir:./uploads}")
    private String uploadDir;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IVideoTaskService videoTaskService;

    @Autowired
    private TaskProcessService taskProcessService;

    @PostConstruct
    public void init() {
        File tempRoot = new File(uploadDir, "temp");
        if (!tempRoot.exists()) {
            tempRoot.mkdirs();
        }
        log.info("Redis 分片上传服务已初始化");
    }

    // ==================== 写操作 ====================

    /**
     * 初始化上传会话（写入 Redis Hash + Set，设置 TTL）
     */
    public UploadSession initUpload(Long userId, String filename, long fileSize,
                                    int totalChunks, int chunkSize) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String tempDir = uploadDir + File.separator + "temp" + File.separator + uploadId;
        new File(tempDir).mkdirs();

        // 写入 Redis Hash
        String sessionKey = KEY_PREFIX_SESSION + uploadId;
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("uploadId", uploadId);
        hash.put("userId", String.valueOf(userId));
        hash.put("filename", filename);
        hash.put("fileSize", String.valueOf(fileSize));
        hash.put("totalChunks", String.valueOf(totalChunks));
        hash.put("chunkSize", String.valueOf(chunkSize));
        hash.put("tempDir", tempDir);
        hash.put("status", "UPLOADING");
        hash.put("createdAt", String.valueOf(System.currentTimeMillis()));

        redis.opsForHash().putAll(sessionKey, hash);
        redis.expire(sessionKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

        // 初始化分片 Set
        String chunksKey = KEY_PREFIX_CHUNKS + uploadId;
        redis.expire(chunksKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

        log.info("分片上传初始化: uploadId={}, filename={}, size={}, chunks={}",
                uploadId, filename, fileSize, totalChunks);

        return loadSession(uploadId);
    }

    /**
     * 保存一个分片（写入磁盘 + 记录索引到 Redis Set）
     */
    public UploadSession saveChunk(String uploadId, int chunkIndex, byte[] data) throws IOException {
        UploadSession session = getSession(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在: " + uploadId);
        }
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new IllegalStateException("上传会话状态异常: " + session.getStatus());
        }

        // 保存分片文件到磁盘
        File chunkFile = new File(session.getTempDir(), "chunk_" + chunkIndex);
        Files.write(chunkFile.toPath(), data);

        // 记录索引到 Redis Set
        redis.opsForSet().add(KEY_PREFIX_CHUNKS + uploadId, String.valueOf(chunkIndex));

        log.debug("分片已保存: uploadId={}, chunk={}", uploadId, chunkIndex);
        return getSession(uploadId);
    }

    /**
     * 合并分片并创建任务
     */
    public VideoTask completeUpload(String uploadId, String originalFilename, Long userId) throws IOException {
        UploadSession session = getSession(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在: " + uploadId);
        }
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new IllegalStateException("上传会话状态异常: " + session.getStatus());
        }

        // 检查分片完整性
        int totalChunks = session.getTotalChunks();
        Set<Integer> received = getReceivedChunkSet(uploadId);
        for (int i = 0; i < totalChunks; i++) {
            if (!received.contains(i)) {
                throw new IllegalStateException("分片不完整，缺少分片: " + i);
            }
        }

        // 合并分片到目标文件
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String mergedFilename = UUID.randomUUID().toString() + ext;
        Path mergedPath = Paths.get(uploadDir, mergedFilename);

        try (FileOutputStream fos = new FileOutputStream(mergedPath.toFile())) {
            for (int i = 0; i < totalChunks; i++) {
                File chunkFile = new File(session.getTempDir(), "chunk_" + i);
                byte[] chunkData = Files.readAllBytes(chunkFile.toPath());
                fos.write(chunkData);
            }
        }

        log.info("分片合并完成: uploadId={}, target={}, size={}",
                uploadId, mergedFilename, Files.size(mergedPath));

        // 计算 MD5
        String md5 = calculateMd5(mergedPath.toFile());

        // 查重（当前用户下是否有相同 MD5 的成功任务）
        VideoTask existing = videoTaskService.lambdaQuery()
                .eq(VideoTask::getMd5, md5)
                .eq(VideoTask::getUserId, userId)
                .eq(VideoTask::getStatus, "SUCCESS")
                .one();

        VideoTask task;
        if (existing != null) {
            Files.deleteIfExists(mergedPath);
            task = existing;
            log.info("分片上传命中缓存: taskId={}", existing.getId());
        } else {
            task = new VideoTask();
            task.setUserId(userId);
            task.setOriginalFilename(originalFilename);
            task.setFilePath(mergedPath.toString());
            task.setMd5(md5);
            task.setStatus("PENDING");
            task.setProgress(0);
            videoTaskService.save(task);
            taskProcessService.asyncProcess(task.getId());
        }

        // 清理 Redis + 临时文件
        cleanup(uploadId);

        return task;
    }

    /**
     * 取消上传，清理 Redis + 临时文件
     */
    public void cancelUpload(String uploadId) {
        cleanup(uploadId);
    }

    // ==================== 读操作 ====================

    /**
     * 获取上传会话
     */
    public UploadSession getSession(String uploadId) {
        String sessionKey = KEY_PREFIX_SESSION + uploadId;
        Map<Object, Object> hash = redis.opsForHash().entries(sessionKey);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return loadSession(uploadId);
    }

    /**
     * 获取已接收的分片索引列表（排序后）
     */
    public List<Integer> getReceivedChunks(String uploadId) {
        return getReceivedChunkSet(uploadId).stream().sorted().collect(Collectors.toList());
    }

    // ==================== 私有方法 ====================

    private UploadSession loadSession(String uploadId) {
        String sessionKey = KEY_PREFIX_SESSION + uploadId;
        Map<Object, Object> hash = redis.opsForHash().entries(sessionKey);
        if (hash == null || hash.isEmpty()) return null;

        UploadSession session = new UploadSession(
                (String) hash.get("uploadId"),
                Long.parseLong((String) hash.get("userId")),
                (String) hash.get("filename"),
                Long.parseLong((String) hash.get("fileSize")),
                Integer.parseInt((String) hash.get("totalChunks")),
                Integer.parseInt((String) hash.get("chunkSize")),
                (String) hash.get("tempDir")
        );
        session.setStatus((String) hash.get("status"));

        long millis = Long.parseLong((String) hash.get("createdAt"));
        session.setCreatedAt(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis), ZoneId.systemDefault()));

        // 从 Redis Set 加载已接收的分片
        Set<Integer> received = getReceivedChunkSet(uploadId);
        session.getReceivedChunks().addAll(received);

        return session;
    }

    private Set<Integer> getReceivedChunkSet(String uploadId) {
        Set<String> members = redis.opsForSet().members(KEY_PREFIX_CHUNKS + uploadId);
        if (members == null || members.isEmpty()) return new HashSet<>();
        return members.stream().map(Integer::parseInt).collect(Collectors.toSet());
    }

    /**
     * 清理 Redis 键 + 磁盘临时文件
     */
    private void cleanup(String uploadId) {
        // 删除 Redis 数据
        redis.delete(KEY_PREFIX_SESSION + uploadId);
        redis.delete(KEY_PREFIX_CHUNKS + uploadId);

        // 删除磁盘临时目录
        String tempDir = uploadDir + File.separator + "temp" + File.separator + uploadId;
        File dir = new File(tempDir);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
        log.info("清理上传资源: uploadId={}", uploadId);
    }

    private String calculateMd5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
