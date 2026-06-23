# 简历项目描述

---

## AiVideoSummary — AI 视频智能摘要服务

**技术栈**：Java 17 · Spring Boot 3 · MyBatis-Plus · MySQL 8 · LangChain4j · SiliconFlow API · ffmpeg · yt-dlp

**项目简介**：
基于 Spring Boot 的 AI 视频摘要服务，支持视频文件上传和 URL 提交，自动完成视频下载、语音转文字、AI 结构化摘要生成的全流程，提供 RESTful API 接口。

**核心工作**：

- **全流程自动化流水线**：设计并实现视频处理状态机（PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → SUCCESS/FAILED），通过 `@Async` 异步编排，支持进度百分比查询
- **并行语音转写优化**：使用 ffmpeg 将音频切片为 90 秒片段，通过 `CompletableFuture` + 线程池实现多片段并行转写（最多 10 并发），显著缩短长视频转写耗时
- **大语言模型集成**：基于 LangChain4j 对接 SiliconFlow Chat API，实现流式摘要输出，支持长文本自动分块、分段摘要后合并再总结的策略，突破 token 长度限制
- **AI 流式输出与持久化**：采用 StreamingChatModel 实时流式输出摘要，每 ~200 字符增量写入数据库，防止长时间处理中数据丢失
- **并发控制与去重**：基于 MD5 文件指纹实现视频去重缓存，通过 `ConcurrentHashMap.newKeySet()` 防止相同视频并发重复处理；使用 `Semaphore` 信号量限制外部 API 并发数，避免触发限流
- **视频源支持**：集成 yt-dlp 工具，支持 B站、抖音等主流平台视频 URL 下载

**产出与收益**：
实现了一个功能完整的 AI 视频摘要原型，代码结构清晰、接口规范，当前持续迭代中。

---

## 精简版（适合简历格子不够时）

**AiVideoSummary — AI 视频智能摘要服务**

Java 17 / Spring Boot 3 / MyBatis-Plus / LangChain4j / SiliconFlow API

基于 Spring Boot 的视频摘要服务，支持上传和 URL 提交，通过 ffmpeg 切片 + 并行调用 Whisper API 转写 + LLM 流式摘要。实现 MD5 去重缓存、状态机进度追踪、Semaphore 并发控制防限流等机制。集成 yt-dlp 支持 B站/抖音等平台视频下载。
