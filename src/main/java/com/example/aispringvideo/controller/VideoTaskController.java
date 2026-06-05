package com.example.aispringvideo.controller;
import lombok.extern.slf4j.Slf4j;
import com.example.aispringvideo.service.IVideoTaskService;
import com.example.aispringvideo.service.TaskProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.aispringvideo.entity.VideoTask;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 视频处理任务表 前端控制器
 * </p>
 *
 * @author yxc
 * @since 2026-06-04
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
public class VideoTaskController {
    @Autowired
    private TaskProcessService taskProcessService;

    @Autowired
    private IVideoTaskService videoTaskService;

    @Value("${video.upload.dir:./uploads}")
    private String uploadDir;

    @PostMapping
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file) {

        // 空文件检测
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件为空");
        }

        String originalFilename = file.getOriginalFilename();

        // 1. 计算 MD5
        String md5;
        try {
            md5 = calculateMd5(file);
        } catch (Exception e) {
            log.error("MD5 计算失败", e);
            return ResponseEntity.internalServerError().body("MD5 计算失败");
        }
        log.info("文件 MD5: {}", md5);

        // 2. 查重：是否已有处理成功的相同视频
        VideoTask existing = videoTaskService.lambdaQuery()
                .eq(VideoTask::getMd5, md5)
                .eq(VideoTask::getStatus, "SUCCESS")
                .one();

        if (existing != null) {
            log.info("命中缓存任务 {}，直接返回现成结果", existing.getId());
            Map<String, Object> result = new HashMap<>();
            result.put("cached", true);
            result.put("taskId", existing.getId());
            result.put("status", existing.getStatus());
            result.put("summary", existing.getSummary());
            result.put("duration", existing.getDuration());
            return ResponseEntity.ok(result);
        }

        // 3. 新文件：保存到本地
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String storedFilename = UUID.randomUUID().toString() + extension;

        Path uploadPath = Paths.get(uploadDir);
        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("上传目录创建失败");
        }

        Path filePath = uploadPath.resolve(storedFilename);
        try {
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            log.error("文件保存失败", e);
            return ResponseEntity.internalServerError().body("文件保存失败: " + e.getMessage());
        }

        // 4. 创建任务并入库
        VideoTask task = new VideoTask();
        task.setOriginalFilename(originalFilename);
        task.setFilePath(filePath.toString());
        task.setMd5(md5);
        task.setStatus("PENDING");
        task.setProgress(0);
        videoTaskService.save(task);

        // 5. 异步处理
        taskProcessService.asyncProcess(task.getId());

        // 6. 返回响应
        Map<String, Object> result = new HashMap<>();
        result.put("cached", false);
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        return ResponseEntity.ok(result);
    }

    /**
     * 通过 URL 提交视频（B站、抖音等）
     */
    @PostMapping("/from-url")
    public ResponseEntity<?> submitUrl(@RequestParam("url") String url) {
        // 基本校验
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("URL 不能为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ResponseEntity.badRequest().body("仅支持 http/https 协议的 URL");
        }

        // 创建任务
        VideoTask task = new VideoTask();
        task.setSourceUrl(url);
        task.setOriginalFilename(url);
        task.setStatus("PENDING");
        task.setProgress(0);
        videoTaskService.save(task);

        // 异步处理
        taskProcessService.asyncProcess(task.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("sourceUrl", url);
        return ResponseEntity.ok(result);
    }

    @RequestMapping("/Json")
    public Map<String, Object> returnJson(int id) {
        Map<String, Object> map = new HashMap<>();
        VideoTask videoTask = videoTaskService.getById(id);
        map.put("data", videoTask);
        return map;
    }

    /**
     * 计算文件的 MD5 值
     */
    private String calculateMd5(MultipartFile file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = file.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
