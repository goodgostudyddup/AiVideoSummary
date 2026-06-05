# AiVideoSummary 🎬 → 📝

AI 视频摘要服务：自动下载视频、语音转文字、AI 生成结构化摘要。

## 功能概览

- **视频输入**：支持文件上传和 URL 提交（B站、抖音等 yt-dlp 支持的平台）
- **语音识别**：基于 SiliconFlow Whisper API 将音频转为文字（支持并行转写）
- **AI 总结**：使用大语言模型生成结构化摘要（标题、核心内容、关键要点、结论亮点）
- **去重缓存**：基于 MD5 检测重复视频，直接返回已生成的摘要
- **异步处理**：任务状态机驱动，支持进度查询

## 技术栈

| 层面 | 技术 | 版本 |
|---|---|---|
| 语言/框架 | Java 17 + Spring Boot 3 | 3.5.14 |
| 构建工具 | Maven (mvnw wrapper) | - |
| ORM | MyBatis-Plus | 3.5.15 |
| 数据库 | MySQL 8 | - |
| AI 编排 | LangChain4j (core + open-ai) | 1.15.0 |
| ASR 语音识别 | SiliconFlow Whisper API (`TeleAI/TeleSpeechASR`) | - |
| LLM 总结 | SiliconFlow Chat API (`tencent/Hunyuan-MT-7B`) | - |
| 视频下载 | yt-dlp | 系统 CLI |
| 音频处理 | ffmpeg | 系统 CLI |

## 项目结构

```
AiVideoSummary/
├── pom.xml                              # Maven 项目配置
├── HELP.md                              # Spring Boot 入门指南
├── LICENSE                              # MIT 许可证
├── summary-prompt.txt                   # 总结提示词草稿
├── uploads/                             # 上传/下载文件临时目录
│
├── src/main/java/com/example/aispringvideo/
│   ├── AispringVideoApplication.java    # 应用入口
│   ├── config/
│   │   └── LangChain4jConfig.java       # ASR + LLM 模型 Bean 配置
│   ├── controller/
│   │   └── VideoTaskController.java     # REST API 控制器
│   ├── entity/
│   │   └── VideoTask.java               # 数据库实体 (video_task 表)
│   ├── mapper/
│   │   └── VideoTaskMapper.java         # MyBatis-Plus Mapper
│   └── service/
│       ├── IVideoTaskService.java       # 服务接口
│       ├── TaskProcessService.java      # 核心编排服务
│       └── impl/
│           └── VideoTaskServiceImpl.java # 服务实现
│
├── src/main/resources/
│   ├── application.properties           # 应用配置
│   └── mapper/
│       └── VideoTaskMapper.xml          # MyBatis XML 映射
│
└── src/test/java/com/example/aispringvideo/
    └── AispringVideoApplicationTests.java
```

## 快速开始

### 前置条件

- JDK 17+
- MySQL 8.x
- ffmpeg（已加入系统 PATH）
- yt-dlp（已加入系统 PATH）
- SiliconFlow API Key（[前往注册](https://siliconflow.cn)）

### 配置

编辑 `src/main/resources/application.properties`：

```properties
# 数据库配置
spring.datasource.url=jdbc:mysql://localhost:3306/video
spring.datasource.username=root
spring.datasource.password=your_password

# SiliconFlow API 配置
siliconflow.api-key=sk-your-api-key-here
siliconflow.base-url=https://api.siliconflow.cn/v1
```

### 运行

```bash
# 使用 Maven 包装器
./mvnw spring-boot:run

# 或打包后运行
./mvnw clean package -DskipTests
java -jar target/AiVideoSummary-0.0.1-SNAPSHOT.jar
```

## API 文档

### 上传视频文件

```http
POST /api/v1/tasks
Content-Type: multipart/form-data

file=@/path/to/video.mp4
```

### 提交视频 URL

```http
POST /api/v1/tasks/from-url
Content-Type: application/x-www-form-urlencoded

url=https://www.bilibili.com/video/BV1xx411c7mD
```

### 查询任务结果

```http
GET /api/v1/tasks/Json?id=1
```

## 核心流程

```
用户上传/URL → PENDING
                 ↓
            DOWNLOADING (yt-dlp 下载音频)   ← 仅 URL 模式
                 ↓
            TRANSCRIBING (SiliconFlow ASR)
                 ├─ ffmpeg 切割 90s 片段
                 └─ 并行 10 线程转写 → 拼接全文
                 ↓
            SUMMARIZING (LLM 总结)
                 ├─ 文本 < 3000 tokens → 直接总结
                 └─ 长文本 → 分块总结 → 合并再总结
                 ↓
            SUCCESS / FAILED
```

### 状态机

| 状态 | 进度 | 说明 |
|---|---|---|
| `PENDING` | 0% | 任务已创建，等待处理 |
| `DOWNLOADING` | 5% | 正在下载视频/音频 |
| `TRANSCRIBING` | 20% | 正在语音转文字 |
| `SUMMARIZING` | 70% | 正在 AI 生成摘要 |
| `SUCCESS` | 100% | 处理完成 |
| `FAILED` | - | 处理失败 |

## 机制说明

- **并行转写**：将音频按 90 秒切片，使用线程池（最多 10 线程）并行调用 ASR API，显著缩短转写时间
- **长文本处理**：当转写文本超过单次 token 限制（3000 tokens）时，自动分块总结后合并再总结
- **MD5 去重**：上传时计算文件 MD5，检测到已成功处理过的视频直接返回缓存结果
- **增量保存**：流式输出时每约 200 字符即写入数据库，防止数据丢失

## TODO / 改进方向

- [ ] **安全加固**：将 API Key 和数据库密码改用环境变量或外部配置
- [ ] **认证鉴权**：REST API 添加访问控制
- [ ] **测试覆盖**：添加核心服务的单元测试和集成测试
- [ ] **配置外化**：使用 `@ConfigurationProperties` 和 Spring Profile
- [ ] **错误处理**：添加全局异常处理器
- [ ] **CI/CD**：配置 GitHub Actions 自动构建
- [ ] **Docker**：容器化部署
- [ ] **监控**：添加日志和性能监控

## 许可证

[MIT](LICENSE)
