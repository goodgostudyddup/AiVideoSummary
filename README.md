# AiVideoSummary 🎬 → 📝

AI 视频摘要服务：自动下载视频、语音转文字、AI 生成结构化摘要。

---

## 项目流程图

### 1. 系统架构总览

```mermaid
flowchart TB
    subgraph 客户端
        Browser["🌐 浏览器 (Vue 3)"]
    end

    subgraph 后端服务 ["后端服务 (Spring Boot 3 / Java 17)"]
        Controller["Controller 层"]
        Service["Service 层"]
        Filter["JwtAuthFilter (鉴权)"]
    end

    subgraph 持久层 ["持久层"]
        MySQL[("MySQL\nvideo_task / user")]
        Redis[("Redis\n分片上传会话")]
        Disk[("磁盘\n视频文件 / 分片")]
    end

    subgraph 外部服务 ["外部服务"]
        SiliconFlow["SiliconFlow API\nWhisper ASR + LLM"]
        ytdlp["yt-dlp\n视频下载"]
        ffmpeg["ffmpeg\n音频切片"]
    end

    Browser -->|"/api/*"| Filter
    Filter --> Controller
    Controller --> Service
    Service --> MySQL
    Service --> Redis
    Service --> Disk
    Service --> SiliconFlow
    Service --> ytdlp
    Service --> ffmpeg
```

---

### 2. 用户鉴权流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端 (Vue 3)
    participant R as 路由守卫
    participant A as 后端 AuthController
    participant DB as MySQL

    U->>F: 访问任意页面
    F->>R: router.beforeEach
    R->>R: 检查 localStorage token
    alt 未登录
        R->>F: 重定向 /login?redirect=原路径
        F->>U: 显示登录页
        U->>F: 输入用户名 + 密码
        F->>A: POST /api/auth/login
        A->>DB: 查询用户
        DB-->>A: 返回用户
        A->>A: BCrypt 验证密码
        A->>A: 生成 JWT (HS256, 7天)
        A-->>F: { token, user }
        F->>F: 存入 localStorage
        F->>R: 跳转 redirect 路径
        R-->>F: 放行
        F-->>U: 显示主页
    else 已登录
        R-->>F: 放行
        F-->>U: 正常访问
    end
```

---

### 3. 视频上传流程（分片上传 + 断点续传）

```mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端 (Vue 3)
    participant B as 后端 ChunkUploadController
    participant S as ChunkUploadService
    participant RD as Redis
    participant DS as 磁盘

    U->>F: 选择视频文件
    F->>F: 判断文件大小

    alt 文件 < 50MB
        F->>B: POST /api/v1/tasks (单次上传)
        B->>DS: 保存文件
        B->>B: 计算 MD5 / 创建任务
        B-->>F: { taskId }
        F-->>U: 上传成功

    else 文件 >= 50MB
        F->>F: 构建 ChunkUploader

        Note over F,RD: ① 检查能否续传
        F->>F: 查 localStorage 找 uploadId
        alt 找到 uploadId
            F->>B: GET /upload/{uploadId} (查状态)
            B->>RD: 取 session + chunks
            RD-->>B: 已接收分片列表
            B-->>F: { receivedChunks: [0,1,3,...] }
            F->>F: 跳过已上传分片
        end

        Note over F,RD: ② 初始化
        F->>B: POST /upload/init
        B->>S: initUpload()
        S->>RD: HSET upload:session:{id}
        S->>RD: EXPIRE 86400
        RD-->>B: OK
        B-->>F: { uploadId }
        F->>F: 存入 localStorage

        Note over F,RD: ③ 上传分片 (3个并发)
        loop 每个缺失分片
            F->>B: POST /upload/{id}/chunk
            B->>DS: 保存 chunk_{n} 文件
            B->>RD: SADD upload:chunks:{id} n
            B-->>F: { chunkIndex: n }
            F->>F: 更新进度条
        end

        Note over F,RD: ④ 完成合并
        F->>B: POST /upload/{id}/complete
        B->>DS: 读取所有分片 → 合并文件
        B->>B: 计算 MD5
        B->>RD: DEL upload:session:{id}
        B->>RD: DEL upload:chunks:{id}
        B->>DS: 删除临时分片文件
        alt MD5 命中缓存
            B-->>F: { cached: true, taskId, summary }
        else
            B->>B: 创建 VideoTask + 异步处理
            B-->>F: { cached: false, taskId }
        end
        F->>F: 清理 localStorage
        F-->>U: 显示成功 / 跳转工作台
    end
```

---

### 4. 视频处理核心流程

```mermaid
flowchart TB
    Start(["任务创建"]) --> CheckSource{"source_url 不为空\n且 file_path 为空？"}
    CheckSource -->|是| Download["🔄 DOWNLOADING (5%)\nyt-dlp 下载音频"]
    CheckSource -->|否| CalcMD5

    Download --> CalcMD5["计算文件 MD5"]

    CalcMD5 --> CacheCheck{"查询 MySQL\n相同 MD5 + 相同 user_id\n存在 SUCCESS 任务？"}

    CacheCheck -->|✅ 命中缓存| ReturnCache["直接返回缓存摘要\n删除新文件"]
    CacheCheck -->|❌ 未命中| Md5Lock{"MD5 正在被\n其他任务处理？"}

    Md5Lock -->|是| WaitPeer["等待最多 60 秒\n轮询其他任务状态"]
    WaitPeer --> PeerDone{"其他任务\n完成？"}
    PeerDone -->|成功| CopyPeer["拷贝结果"]
    PeerDone -->|失败| FailTask["❌ FAILED"]
    PeerDone -->|超时| FailTask

    Md5Lock -->|否| Transcribe["🔄 TRANSCRIBING (20%)"]

    subgraph 转写阶段 ["转写阶段 (TRANSCRIBING)"]
        direction TB
        Slice["ffmpeg 切片 90s/段"] --> Parallel["并行转写 (最多10线程)"]
        Parallel --> EachChunk["对每段调用\nSiliconFlow Whisper API"]
        EachChunk --> Semaphore["Semaphore.acquire()\n(全局限流 max=5)"]
        Semaphore --> ASR["TeleAI/TeleSpeechASR\n语音转文字"]
        ASR --> SemRel["Semaphore.release()"]
        SemRel --> Concat["拼接所有分段结果"]
    end

    Transcribe --> Slice
    Concat --> Summarize["🔄 SUMMARIZING (70%)"]

    subgraph 摘要阶段 ["摘要阶段 (SUMMARIZING)"]
        direction TB
        CheckLen{"转写文本\n< 3000 tokens？"}
        CheckLen -->|是| Stream["流式调用 LLM\n直接生成摘要"]
        CheckLen -->|否| Split["按段落分块"]
        Split --> ChunkSum["对每块调用\n非流式 Chat API"]
        ChunkSum --> Merge["合并分段摘要"]
        Merge --> Stream2["流式调用 LLM\n综合生成最终摘要"]

        Stream --> SaveInc["每 ~200 字符\n增量写入 MySQL"]
        Stream2 --> SaveInc2["每 ~200 字符\n增量写入 MySQL"]
    end

    Summarize --> CheckLen

    SaveInc2 --> Done["✅ SUCCESS (100%)\n记录总用时"]
    SaveInc --> Done

    Done --> CleanUp["删除临时源文件\n释放 MD5 锁"]
```

---

### 5. 前端页面路由

```mermaid
flowchart LR
    Login["/login\n登录页"] --> Home
    Register["/register\n注册页"] --> Home

    Home["/\n主页"] --> UploadURL["🔗 URL 上传面板"]
    Home --> UploadFile["📁 文件上传面板"]

    UploadFile --> SmallFile["小文件 < 50MB\n单次上传 POST /tasks"]
    UploadFile --> LargeFile["大文件 >= 50MB\n分片上传 POST /upload/*"]

    Workbench["/workbench\n工作台"] --> TaskList["任务列表\n每 5s 自动刷新"]
    TaskList --> Detail["查看详情弹窗\n转写文本 + AI 摘要"]

    Home --> Workbench

    classDef auth fill:#fef3c7,stroke:#f59e0b
    classDef page fill:#eef2ff,stroke:#6366f1

    class Login,Register auth
    class Home,Workbench page
```

---

## 技术栈

| 层面 | 技术 | 版本 |
|---|---|---|
| 语言/框架 | Java 17 + Spring Boot 3 | 3.5.14 |
| 构建工具 | Maven (mvnw wrapper) | - |
| ORM | MyBatis-Plus | 3.5.15 |
| 数据库 | MySQL 8 | - |
| 缓存 | Redis | - |
| AI 编排 | LangChain4j (core + open-ai) | 1.15.0 |
| ASR 语音识别 | SiliconFlow Whisper API (`TeleAI/TeleSpeechASR`) | - |
| LLM 总结 | SiliconFlow Chat API (`tencent/Hunyuan-MT-7B`) | - |
| 视频下载 | yt-dlp | 系统 CLI |
| 音频处理 | ffmpeg | 系统 CLI |

---

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
│   │   ├── LangChain4jConfig.java       # ASR + LLM 模型 Bean 配置
│   │   └── WebConfig.java              # JWT Filter 注册
│   ├── auth/
│   │   ├── JwtUtil.java                # JWT 生成/验证
│   │   └── JwtAuthFilter.java          # 鉴权过滤器
│   ├── controller/
│   │   ├── AuthController.java         # 注册/登录
│   │   ├── VideoTaskController.java    # 视频任务 API
│   │   └── ChunkUploadController.java  # 分片上传 API
│   ├── entity/
│   │   ├── User.java                   # 用户实体
│   │   ├── VideoTask.java              # 视频任务实体
│   │   └── UploadSession.java          # 上传会话模型（内存）
│   ├── mapper/
│   │   ├── UserMapper.java
│   │   └── VideoTaskMapper.java
│   └── service/
│       ├── UserService.java + impl
│       ├── IVideoTaskService.java + impl
│       ├── TaskProcessService.java     # 核心编排服务
│       └── ChunkUploadService.java     # 分片上传 + Redis
│
├── src/main/resources/
│   ├── application.properties           # 应用配置
│   └── mapper/
│       └── VideoTaskMapper.xml
│
└── sql/
    └── migration.sql                    # 数据库迁移
```

---

## 快速开始

### 前置条件

- JDK 17+
- MySQL 8.x
- Redis
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

---

## API 文档

### 用户鉴权

```http
POST /api/auth/register
Content-Type: application/json

{ "username": "xxx", "password": "123456" }

Response: { "token": "eyJ...", "user": { "id": 1, "username": "xxx" } }
```

```http
POST /api/auth/login
Content-Type: application/json

{ "username": "xxx", "password": "123456" }

Response: { "token": "eyJ...", "user": { "id": 1, "username": "xxx" } }
```

### 上传视频文件

```http
POST /api/v1/tasks
Content-Type: multipart/form-data
Authorization: Bearer <token>

file=@/path/to/video.mp4
```

### 分片上传

```http
# 1. 初始化
POST /api/v1/tasks/upload/init
Authorization: Bearer <token>
Content-Type: application/json

{ "filename": "demo.mp4", "fileSize": 524288000, "totalChunks": 100, "chunkSize": 5242880 }
→ { "uploadId": "abc123" }

# 2. 上传单个分片
POST /api/v1/tasks/upload/{uploadId}/chunk
Authorization: Bearer <token>
Content-Type: multipart/form-data

chunk=0  file=@chunk_data
→ { "chunkIndex": 0 }

# 3. 查询已上传分片（用于续传）
GET /api/v1/tasks/upload/{uploadId}
Authorization: Bearer <token>
→ { "receivedChunks": [0,1,2,4,5], "totalChunks": 100, "progress": 72 }

# 4. 完成合并
POST /api/v1/tasks/upload/{uploadId}/complete
Authorization: Bearer <token>
Content-Type: multipart/form-data

filename=demo.mp4
→ { "taskId": 42, "cached": false }
```

### 提交视频 URL

```http
POST /api/v1/tasks/from-url
Authorization: Bearer <token>
Content-Type: application/x-www-form-urlencoded

url=https://www.bilibili.com/video/BV1xx411c7mD
```

### 查询任务

```http
GET /api/v1/tasks/list
Authorization: Bearer <token>
→ { "data": [ { "id": 42, "status": "TRANSCRIBING", ... } ] }

GET /api/v1/tasks/Json?id=42
Authorization: Bearer <token>
→ { "data": { "id": 42, "transcript": "...", "summary": "..." } }
```

---

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

---

## 机制说明

- **并行转写**：将音频按 90 秒切片，使用线程池（最多 10 线程）并行调用 ASR API，显著缩短转写时间
- **长文本处理**：当转写文本超过单次 token 限制（3000 tokens）时，自动分块总结后合并再总结
- **流式输出**：采用 StreamingChatModel 实时流式输出摘要，每 ~200 字符增量写入数据库
- **MD5 去重**：上传时计算文件 MD5，检测到已成功处理过的视频直接返回缓存结果
- **分片上传**：大文件（>50MB）自动切换分片上传，3 并发，支持断点续传和自动重试
- **Semaphore 限流**：全局信号量限制 SiliconFlow API 并发，避免触发限流
- **JWT 鉴权**：注册/登录后获取 JWT，后续请求通过 Authorization header 验证，任务按用户隔离

---

## TODO / 改进方向

- [ ] **安全加固**：将 API Key 和数据库密码改用环境变量或外部配置
- [ ] **错误处理**：添加全局异常处理器
- [ ] **CI/CD**：配置 GitHub Actions 自动构建
- [ ] **Docker**：容器化部署
- [ ] **监控**：添加日志和性能监控

## 许可证

[MIT](LICENSE)
