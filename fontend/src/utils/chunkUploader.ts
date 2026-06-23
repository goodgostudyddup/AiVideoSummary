import { authFetch } from './api'

export interface UploadProgress {
  percent: number
  loaded: number
  total: number
  speed: string // e.g. "3.2 MB/s"
  chunkIndex: number
  totalChunks: number
}

export interface ChunkUploaderOptions {
  chunkSize?: number   // 分片大小，默认 5MB
  concurrency?: number // 并发数，默认 3
  maxRetries?: number  // 每个分片最大重试次数，默认 3
}

export interface ChunkUploadResult {
  taskId: number
  cached: boolean
  status: string
  summary?: string
  duration?: number
}

const SPEED_WINDOW = 5 // 取最近 5 次采样算速度

export class ChunkUploader {
  private file: File
  private chunkSize: number
  private concurrency: number
  private maxRetries: number
  private uploadId: string = ''
  private uploadedChunks: Set<number> = new Set()
  private aborted = false

  // 速度计算
  private speedSamples: { time: number; loaded: number }[] = []
  private lastLoaded = 0

  // 进度回调
  onProgress: (p: UploadProgress) => void = () => {}
  onError: (err: string) => void = () => {}
  onChunkComplete: (idx: number) => void = () => {}

  constructor(file: File, options?: ChunkUploaderOptions) {
    this.file = file
    this.chunkSize = options?.chunkSize || 5 * 1024 * 1024 // 默认 5MB
    this.concurrency = options?.concurrency || 3
    this.maxRetries = options?.maxRetries || 3
  }

  get totalChunks(): number {
    return Math.ceil(this.file.size / this.chunkSize)
  }

  /**
   * 开始上传，返回最终结果
   */
  async start(): Promise<ChunkUploadResult> {
    this.aborted = false
    this.uploadedChunks.clear()
    this.speedSamples = []
    this.lastLoaded = 0

    // 1. 检查是否有可续传的上传
    const savedUploadId = localStorage.getItem(`upload_${this.file.name}_${this.file.size}`)
    if (savedUploadId) {
      this.uploadId = savedUploadId
      // 检查服务端是否还有这个会话
      const exists = await this.checkSessionExists()
      if (!exists) {
        localStorage.removeItem(`upload_${this.file.name}_${this.file.size}`)
        this.uploadId = ''
      }
    }

    // 2. 如果没有已有会话，初始化新上传
    if (!this.uploadId) {
      const { uploadId } = await this.initUpload()
      this.uploadId = uploadId
      localStorage.setItem(`upload_${this.file.name}_${this.file.size}`, uploadId)
    }

    // 3. 查询已上传的分片
    await this.fetchUploadedChunks()

    // 4. 上传缺失的分片
    await this.uploadRemainingChunks()

    // 5. 完成上传
    const result = await this.completeUpload()

    // 清理续传记录
    localStorage.removeItem(`upload_${this.file.name}_${this.file.size}`)

    return result
  }

  /**
   * 取消上传
   */
  cancel() {
    this.aborted = true
    if (this.uploadId) {
      authFetch(`/api/v1/tasks/upload/${this.uploadId}`, { method: 'DELETE' }).catch(() => {})
      localStorage.removeItem(`upload_${this.file.name}_${this.file.size}`)
    }
  }

  // ==================== 内部方法 ====================

  private async initUpload(): Promise<{ uploadId: string }> {
    const res = await authFetch('/api/v1/tasks/upload/init', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        filename: this.file.name,
        fileSize: this.file.size,
        totalChunks: this.totalChunks,
        chunkSize: this.chunkSize,
      }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '初始化上传失败')
    return data
  }

  private async checkSessionExists(): Promise<boolean> {
    try {
      const res = await authFetch(`/api/v1/tasks/upload/${this.uploadId}`)
      return res.ok
    } catch {
      return false
    }
  }

  private async fetchUploadedChunks() {
    if (!this.uploadId) return
    try {
      const res = await authFetch(`/api/v1/tasks/upload/${this.uploadId}`)
      if (res.ok) {
        const data = await res.json()
        if (data.receivedChunks) {
          this.uploadedChunks = new Set(data.receivedChunks)
        }
      }
    } catch {
      // 忽略，从头开始上传
    }
  }

  private async uploadRemainingChunks() {
    // 构建需要上传的分片索引列表
    const pending: number[] = []
    for (let i = 0; i < this.totalChunks; i++) {
      if (!this.uploadedChunks.has(i)) {
        pending.push(i)
      }
    }

    if (pending.length === 0) return

    // 并发上传
    let idx = 0
    const totalPending = pending.length

    const runWorker = async () => {
      while (idx < pending.length && !this.aborted) {
        const chunkIdx = pending[idx++]!
        await this.uploadSingleChunkWithRetry(chunkIdx)

        if (!this.aborted) {
          this.uploadedChunks.add(chunkIdx)
          this.onChunkComplete(chunkIdx)

          // 计算进度
          const loaded = this.uploadedChunks.size * this.chunkSize
          this.updateSpeed(loaded)
          const percent = Math.min(100, Math.round((this.uploadedChunks.size / this.totalChunks) * 100))

          this.onProgress({
            percent,
            loaded: Math.min(loaded, this.file.size),
            total: this.file.size,
            speed: this.formatSpeed(),
            chunkIndex: chunkIdx,
            totalChunks: this.totalChunks,
          })
        }
      }
    }

    // 启动 N 个并发 worker
    const workers = []
    for (let i = 0; i < Math.min(this.concurrency, totalPending); i++) {
      workers.push(runWorker())
    }
    await Promise.all(workers)

    if (this.aborted) {
      throw new Error('上传已取消')
    }
  }

  private async uploadSingleChunkWithRetry(chunkIndex: number): Promise<void> {
    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      if (this.aborted) return
      try {
        await this.uploadSingleChunk(chunkIndex)
        return
      } catch (e: any) {
        if (this.aborted) return
        if (attempt < this.maxRetries) {
          // 指数退避
          const delay = Math.min(1000 * Math.pow(2, attempt), 8000)
          await new Promise((r) => setTimeout(r, delay))
        } else {
          this.onError(`分片 ${chunkIndex} 上传失败: ${e.message}`)
          throw new Error(`分片 ${chunkIndex} 上传失败，已重试 ${this.maxRetries} 次`)
        }
      }
    }
  }

  private async uploadSingleChunk(chunkIndex: number): Promise<void> {
    const start = chunkIndex * this.chunkSize
    const end = Math.min(start + this.chunkSize, this.file.size)
    const blob = this.file.slice(start, end)

    const formData = new FormData()
    formData.append('chunk', String(chunkIndex))
    formData.append('file', blob, `chunk_${chunkIndex}`)

    const res = await authFetch(`/api/v1/tasks/upload/${this.uploadId}/chunk`, {
      method: 'POST',
      body: formData,
    })

    if (!res.ok) {
      const data = await res.json().catch(() => ({}))
      throw new Error(data.error || `HTTP ${res.status}`)
    }
  }

  private async completeUpload(): Promise<ChunkUploadResult> {
    const formData = new FormData()
    formData.append('filename', this.file.name)

    const res = await authFetch(`/api/v1/tasks/upload/${this.uploadId}/complete`, {
      method: 'POST',
      body: formData,
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '合并文件失败')
    return data as ChunkUploadResult
  }

  // ==================== 速度计算 ====================

  private updateSpeed(loaded: number) {
    const now = Date.now()
    this.speedSamples.push({ time: now, loaded })

    // 只保留最近 N 个采样点
    if (this.speedSamples.length > SPEED_WINDOW) {
      this.speedSamples.shift()
    }
    this.lastLoaded = loaded
  }

  private formatSpeed(): string {
    if (this.speedSamples.length < 2) return '计算中...'

    const first = this.speedSamples[0]
    const last = this.speedSamples[this.speedSamples.length - 1]
    
    // 安全检查：确保 first 和 last 不为 undefined
    if (!first || !last) return '计算中...'
    
    const dt = (last.time - first.time) / 1000
    if (dt < 0.5) return '计算中...'

    const bytes = last.loaded - first.loaded
    const bytesPerSec = bytes / dt

    if (bytesPerSec > 1024 * 1024) {
      return (bytesPerSec / 1024 / 1024).toFixed(1) + ' MB/s'
    } else if (bytesPerSec > 1024) {
      return (bytesPerSec / 1024).toFixed(0) + ' KB/s'
    } else {
      return (bytesPerSec).toFixed(0) + ' B/s'
    }
  }
}
