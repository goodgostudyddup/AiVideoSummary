package com.example.aispringvideo.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.aispringvideo.entity.VideoTask;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.audio.AudioTranscriptionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Service
@Slf4j
public class TaskProcessService {

    @Value("${siliconflow.max-concurrent}")
    private int maxConcurrentApiCalls;

    /** 全局信号量，限制对 SiliconFlow API 的并发请求数 */
    private Semaphore siliconflowSemaphore;

    @PostConstruct
    public void init() {
        this.siliconflowSemaphore = new Semaphore(maxConcurrentApiCalls, true);
        log.info("SiliconFlow API 并发限制已初始化: max={}", maxConcurrentApiCalls);

        // 如果 Cookie 文件不在磁盘上，从 JAR 解压到临时目录
        if (ytDlpCookiesPath != null && !ytDlpCookiesPath.isBlank()) {
            File f = new File(ytDlpCookiesPath);
            if (!f.exists()) {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(ytDlpCookiesPath)) {
                    if (is != null) {
                        File tmp = File.createTempFile("bilibili_cookies_", ".txt");
                        Files.copy(is, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        tmp.deleteOnExit();
                        ytDlpCookiesPath = tmp.getAbsolutePath();
                        log.info("Cookie 已解压到: {}", ytDlpCookiesPath);
                    }
                } catch (IOException e) {
                    log.warn("Cookie 文件处理失败", e);
                }
            }
        }
    }

    /** 正在处理的 MD5 集合，防止相同视频重复处理 */
    private final Set<String> processingMd5s = ConcurrentHashMap.newKeySet();

    @Autowired
    private IVideoTaskService taskService;

    @Autowired
    private AudioTranscriptionModel audioTranscriptionModel;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${yt-dlp.path:yt-dlp}")
    private String ytDlpPath;

    @Value("${yt-dlp.cookies-path:}")
    private String ytDlpCookiesPath;

    @Value("${video.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${siliconflow.gpt.summary-prompt:请用中文对以下文本进行要点总结，要求语言简洁，不超过300字。文本内容：\n}")
    private String summaryPrompt;

    @Value("${siliconflow.gpt.max-tokens-per-chunk:3000}")
    private int maxTokensPerChunk;

    /**
     * 异步处理任务主流程
     */
    @Async
    public void asyncProcess(Long taskId) {
        log.info("开始处理任务 {}", taskId);
        VideoTask task = taskService.getById(taskId);
        if (task == null) {
            log.error("任务 {} 不存在", taskId);
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            // ── URL 任务：先下载音频 ──
            if (task.getSourceUrl() != null && task.getFilePath() == null) {
                updateTaskStatus(taskId, "DOWNLOADING", 5);
                log.info("从 URL 下载音频: {}", task.getSourceUrl());
                String downloadedPath = downloadAudioFromUrl(task.getSourceUrl());
                task.setFilePath(downloadedPath);
                task.setOriginalFilename(new File(downloadedPath).getName());
                taskService.updateById(task);
            }

            // ── 计算 MD5 并查重 ──
            if (task.getMd5() == null) {
                String md5 = calculateFileMd5(new File(task.getFilePath()));
                task.setMd5(md5);
                taskService.updateById(task);

                VideoTask cached = taskService.lambdaQuery()
                        .eq(VideoTask::getMd5, md5)
                        .eq(VideoTask::getStatus, "SUCCESS")
                        .ne(VideoTask::getId, taskId)
                        .one();
                if (cached != null) {
                    log.info("命中 MD5 缓存，直接使用任务 {} 的摘要", cached.getId());
                    task.setTranscript(cached.getTranscript());
                    task.setSummary(cached.getSummary());
                    task.setDuration(cached.getDuration());
                    updateTaskStatus(taskId, "SUCCESS", 100);
                    taskService.updateById(task);
                    return;
                }
            }

            // ── 注册 MD5，防止并发重复处理 ──
            String md5 = task.getMd5();
            if (md5 != null && !processingMd5s.add(md5)) {
                log.info("MD5 {} 正在被其他任务处理，等待...", md5);
                for (int i = 0; i < 60; i++) {
                    VideoTask peer = taskService.lambdaQuery()
                            .eq(VideoTask::getMd5, md5)
                            .in(VideoTask::getStatus, "SUCCESS", "FAILED")
                            .ne(VideoTask::getId, taskId)
                            .one();
                    if (peer != null) {
                        if ("SUCCESS".equals(peer.getStatus())) {
                            task.setTranscript(peer.getTranscript());
                            task.setSummary(peer.getSummary());
                            task.setDuration(peer.getDuration());
                            updateTaskStatus(taskId, "SUCCESS", 100);
                            taskService.updateById(task);
                        } else {
                            task.setStatus("FAILED");
                            task.setErrorMessage("相同视频的另一任务处理失败");
                            taskService.updateById(task);
                        }
                        return;
                    }
                    Thread.sleep(3000);
                }
                task.setStatus("FAILED");
                task.setErrorMessage("等待其他任务处理超时");
                taskService.updateById(task);
                return;
            }

            // ── 转写 ──
            updateTaskStatus(taskId, "TRANSCRIBING", 20);
            String transcript = transcribeFromVideo(task.getFilePath());
            task.setTranscript(transcript);
            taskService.updateById(task);

            // ── AI 摘要（流式输出） ──
            updateTaskStatus(taskId, "SUMMARIZING", 70);
            String summary = generateSummary(transcript, taskId);
            task.setSummary(summary);
            taskService.updateById(task);

            // ── 成功 ──
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            task.setDuration(elapsed);
            updateTaskStatus(taskId, "SUCCESS", 100);
            log.info("任务 {} 处理成功，总用时 {} 秒", taskId, elapsed);
            taskService.lambdaUpdate().eq(VideoTask::getId, taskId).set(VideoTask::getDuration, elapsed).update();

        } catch (Exception e) {
            log.error("任务 {} 处理失败", taskId, e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskService.updateById(task);
        } finally {
            // 释放 MD5 锁，让等待的任务可以继续
            String md5 = task.getMd5();
            if (md5 != null) processingMd5s.remove(md5);

            String filePath = task.getFilePath();
            if (filePath != null) {
                File sourceFile = new File(filePath);
                if (sourceFile.exists()) {
                    boolean deleted = sourceFile.delete();
                    log.info("清理源文件 {}: {}", filePath, deleted ? "成功" : "失败");
                }
            }
        }
    }

    // ==================== 语音转写相关 ====================

    private String transcribeFromVideo(String videoPath) throws IOException {
        File videoFile = new File(videoPath);
        log.info("从语音分段转写: {}", videoFile.getName());
        return transcribeLargeAudio(videoFile);
    }

    /**
     * 强制切片转写：切成 90 秒一段，并行调用 Whisper API 再按顺序拼接
     */
    private String transcribeLargeAudio(File videoFile) throws IOException {
        log.info("开始从视频直接切片转写...");
        String baseName = videoFile.getAbsolutePath().replaceFirst("[.][^.]+$", "");
        String segmentPattern = baseName + "_segment_%03d.m4a";

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-i", videoFile.getAbsolutePath(),
                    "-vn", "-map", "0:a:0",
                    "-f", "segment", "-segment_time", "90",
                    "-c:a", "aac", "-b:a", "32k", "-ar", "16000", "-ac", "1",
                    "-reset_timestamps", "1",
                    "-y", segmentPattern);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            new Thread(() -> {
                try (var is = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    while (is.read(buf) != -1) {}
                } catch (IOException ignored) {}
            }).start();

            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new RuntimeException("音频切片失败，exitCode=" + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("音频切片被中断", e);
        }

        File dir = videoFile.getParentFile();
        String prefix = baseName.substring(baseName.lastIndexOf(File.separator) + 1) + "_segment_";
        File[] segments = dir.listFiles((d, name) -> name.startsWith(prefix));
        if (segments == null || segments.length == 0) {
            throw new RuntimeException("未找到切片文件");
        }
        Arrays.sort(segments, Comparator.comparing(File::getName));

        int poolSize = Math.min(segments.length, 10);
        log.info("开始并行转写 {} 个切片（并发 {}，每段 90s）...", segments.length, poolSize);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<String>[] futures = new CompletableFuture[segments.length];
            for (int i = 0; i < segments.length; i++) {
                int idx = i;
                File seg = segments[i];
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        siliconflowSemaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("等待 SiliconFlow 信号量被中断", e);
                    }
                    try {
                        log.info("转写切片 {}/{}：{}（当前并发: {}/{}）",
                                idx + 1, segments.length, seg.getName(),
                                maxConcurrentApiCalls - siliconflowSemaphore.availablePermits(),
                                maxConcurrentApiCalls);
                        byte[] audioBytes = Files.readAllBytes(seg.toPath());
                        Audio audio = Audio.builder().binaryData(audioBytes).mimeType("audio/mp4").build();
                        String text = audioTranscriptionModel.transcribe(
                                AudioTranscriptionRequest.builder(audio).build()).text().trim();
                        seg.delete();
                        return text;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        siliconflowSemaphore.release();
                    }
                }, executor);
            }

            CompletableFuture.allOf(futures).join();

            StringBuilder sb = new StringBuilder();
            for (CompletableFuture<String> f : futures) {
                sb.append(f.join()).append(" ");
            }
            return sb.toString().trim();
        } catch (CompletionException e) {
            for (File seg : segments) {
                if (seg.exists()) seg.delete();
            }
            Throwable cause = e.getCause();
            throw new IOException("并行转写出错", cause);
        } finally {
            executor.shutdown();
        }
    }

    // ==================== AI 摘要相关 ====================

    private String generateSummary(String transcript, Long taskId) {
        if (transcript == null || transcript.isBlank()) {
            return "无内容可总结";
        }
        int approxTokens = transcript.length() / 2;
        if (approxTokens <= maxTokensPerChunk) {
            return callGptApiStreaming(summaryPrompt, transcript, taskId);
        } else {
            return summarizeLongText(transcript, taskId);
        }
    }

    private String summarizeLongText(String text, Long taskId) {
        String[] paragraphs = text.split("\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentLength = 0;
        int chunkTokenLimit = maxTokensPerChunk * 2;

        for (String para : paragraphs) {
            if (currentLength + para.length() > chunkTokenLimit && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentLength = 0;
            }
            currentChunk.append(para).append("\n");
            currentLength += para.length();
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        List<String> partialSummaries = new ArrayList<>();
        for (String chunk : chunks) {
            String summary = callGptApi(summaryPrompt, chunk);
            partialSummaries.add(summary);
        }

        String combined = String.join("\n", partialSummaries);
        return callGptApiStreaming(summaryPrompt + "（这是多个分段的总结，请综合成最终总结）\n", combined, taskId);
    }

    /**
     * 非流式调用 Chat API（用于中间分段摘要，通过 LangChain4j）
     */
    private String callGptApi(String promptPrefix, String content) {
        try {
            siliconflowSemaphore.acquire();
            log.info("非流式 Chat API 调用（当前并发: {}/{}）",
                    maxConcurrentApiCalls - siliconflowSemaphore.availablePermits(),
                    maxConcurrentApiCalls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 SiliconFlow 信号量被中断", e);
        }
        try {
            ChatResponse response = chatModel.chat(UserMessage.from(promptPrefix + content));
            String text = response.aiMessage().text();
            if (text == null) throw new RuntimeException("Chat API 返回空内容");
            return text;
        } finally {
            siliconflowSemaphore.release();
        }
    }

    /**
     * 流式调用 Chat API（用于最终摘要，通过 LangChain4j StreamingChatModel）
     * token 逐个打印到控制台，同时每 ~200 字符存一次数据库
     */
    private String callGptApiStreaming(String promptPrefix, String content, Long taskId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(promptPrefix + content));

        try {
            siliconflowSemaphore.acquire();
            log.info("流式 Chat API 调用（当前并发: {}/{}）",
                    maxConcurrentApiCalls - siliconflowSemaphore.availablePermits(),
                    maxConcurrentApiCalls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 SiliconFlow 信号量被中断", e);
        }

        StringBuilder fullContent = new StringBuilder();
        int[] lastSaveLength = {0};
        CompletableFuture<String> future = new CompletableFuture<>();

        streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                fullContent.append(token);
//                System.out.print(token);
//                System.out.flush();

                if (taskId != null && fullContent.length() - lastSaveLength[0] > 200) {
                    VideoTask partial = new VideoTask();
                    partial.setId(taskId);
                    partial.setSummary(fullContent.toString());
                    taskService.updateById(partial);
                    lastSaveLength[0] = fullContent.length();
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    System.out.println();
                    String result = fullContent.toString().trim();
                    if (taskId != null) {
                        VideoTask partial = new VideoTask();
                        partial.setId(taskId);
                        partial.setSummary(result);
                        taskService.updateById(partial);
                        log.info("摘要最终保存完成，共 {} 字符", result.length());
                    }
                    future.complete(result);
                } finally {
                    siliconflowSemaphore.release();
                }
            }

            @Override
            public void onError(Throwable error) {
                siliconflowSemaphore.release();
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("流式摘要被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException("流式摘要失败", cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("流式摘要超时", e);
        }
    }

    // ==================== 工具方法 ====================

    private String downloadAudioFromUrl(String url) throws IOException, InterruptedException {
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();

        String outputTemplate = dir.getAbsolutePath() + "/%(id)s.%(ext)s";
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ytDlpPath);
        cmd.add(url);
        cmd.add("-f");
        cmd.add("bestaudio");
        cmd.add("-x");
        cmd.add("--audio-format");
        cmd.add("mp3");
        cmd.add("--audio-quality");
        cmd.add("32k");
        cmd.add("--user-agent");
        cmd.add(userAgent);
        cmd.add("--referer");
        cmd.add("https://www.bilibili.com");
        cmd.add("--add-header");
        cmd.add("Origin:https://www.bilibili.com");
        cmd.add("--add-header");
        cmd.add("Accept-Language:zh-CN,zh;q=0.9,en;q=0.8");
        cmd.add("--add-header");
        cmd.add("Accept:text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (ytDlpCookiesPath != null && !ytDlpCookiesPath.isBlank()) {
            cmd.add("--cookies");
            cmd.add(ytDlpCookiesPath);
        }
        cmd.add("-o");
        cmd.add(outputTemplate);
        cmd.add("--no-playlist");
        cmd.add("--newline");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 实时读取输出，打印进度
        int exitCode;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("%") || line.startsWith("[download]")) {
                    log.info("[yt-dlp] {}", line.trim());
                }
            }
            exitCode = process.waitFor();
        }

        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp 下载失败，exitCode=" + exitCode);
        }

        File[] candidates = dir.listFiles((d, name) -> name.endsWith(".mp3"));
        if (candidates == null || candidates.length == 0) {
            throw new RuntimeException("yt-dlp 下载完成但未找到 MP3 文件");
        }
        File newest = Arrays.stream(candidates)
                .max(Comparator.comparingLong(File::lastModified))
                .orElseThrow(() -> new RuntimeException("找不到下载的 MP3"));
        log.info("音频下载完成: {}", newest.getAbsolutePath());
        return newest.getAbsolutePath();
    }

    private String calculateFileMd5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream is = new FileInputStream(file)) {
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

    private void updateTaskStatus(Long taskId, String status, int progress) {
        LambdaUpdateWrapper<VideoTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(VideoTask::getId, taskId)
                .set(VideoTask::getStatus, status)
                .set(VideoTask::getProgress, progress);
        taskService.update(wrapper);
    }
}
