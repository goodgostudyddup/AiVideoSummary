<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authFetch } from '../utils/api'
import { ChunkUploader, type UploadProgress } from '../utils/chunkUploader'

const router = useRouter()

// URL 上传
const urlInput = ref('')
const urlLoading = ref(false)
const urlError = ref('')
const urlResult = ref<any>(null)

async function submitUrl() {
  const url = urlInput.value.trim()
  if (!url) {
    urlError.value = '请输入视频 URL'
    return
  }
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    urlError.value = '仅支持 http/https 协议的 URL'
    return
  }

  urlError.value = ''
  urlLoading.value = true
  urlResult.value = null

  try {
    const params = new URLSearchParams()
    params.append('url', url)
    const res = await authFetch('/api/v1/tasks/from-url', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params
    })
    const data = await res.json()
    if (!res.ok) {
      urlError.value = data.message || '提交失败'
    } else {
      urlResult.value = data
      urlInput.value = ''
    }
  } catch (e: any) {
    urlError.value = '网络错误: ' + e.message
  } finally {
    urlLoading.value = false
  }
}

// 本地文件上传
const selectedFile = ref<File | null>(null)
const fileLoading = ref(false)
const fileError = ref('')
const fileResult = ref<any>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 分片上传相关
const uploadProgress = ref<UploadProgress | null>(null)
const uploaderRef = ref<ChunkUploader | null>(null)
const CHUNK_THRESHOLD = 50 * 1024 * 1024 // 50MB

function onFileSelected(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    selectedFile.value = target.files[0]!
    fileError.value = ''
    fileResult.value = null
    uploadProgress.value = null
  }
}

function removeFile() {
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  uploadProgress.value = null
  uploaderRef.value = null
}

async function uploadFile() {
  if (!selectedFile.value) {
    fileError.value = '请选择要上传的视频文件'
    return
  }

  fileError.value = ''
  fileLoading.value = true
  fileResult.value = null
  uploadProgress.value = null

  const file = selectedFile.value

  // 小文件直接用单次上传
  if (file.size < CHUNK_THRESHOLD) {
    try {
      const formData = new FormData()
      formData.append('file', file)
      const res = await authFetch('/api/v1/tasks', {
        method: 'POST',
        body: formData
      })
      const data = await res.json()
      if (!res.ok) {
        fileError.value = data.message || '上传失败'
      } else {
        fileResult.value = data
        removeFile()
      }
    } catch (e: any) {
      fileError.value = '网络错误: ' + e.message
    } finally {
      fileLoading.value = false
    }
    return
  }

  // 大文件使用分片上传
  const uploader = new ChunkUploader(file, {
    concurrency: 3,
    maxRetries: 3,
  })
  uploaderRef.value = uploader

  // 进度回调
  uploader.onProgress = (p: UploadProgress) => {
    uploadProgress.value = p
  }

  // 错误回调
  uploader.onError = (err: string) => {
    fileError.value = err
  }

  try {
    const result = await uploader.start()
    fileResult.value = result
    removeFile()
  } catch (e: any) {
    if (e.message === '上传已取消') {
      fileError.value = '上传已取消'
    } else {
      fileError.value = '上传失败: ' + e.message
    }
  } finally {
    fileLoading.value = false
    uploaderRef.value = null
  }
}

function cancelUpload() {
  if (uploaderRef.value) {
    uploaderRef.value.cancel()
    uploaderRef.value = null
    uploadProgress.value = null
    fileLoading.value = false
    fileError.value = '上传已取消'
  }
}

function goToWorkbench() {
  router.push('/workbench')
}

function formatSize(bytes: number): string {
  if (bytes > 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB'
  if (bytes > 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  if (bytes > 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return bytes + ' B'
}
</script>

<template>
  <div class="home">
    <div class="hero">
      <h1 class="hero-title">AI 视频智能摘要</h1>
      <p class="hero-desc">上传视频或粘贴链接，AI 自动转写文字并生成结构化摘要</p>
    </div>

    <div class="upload-panels">
      <!-- URL 上传 -->
      <div class="panel">
        <div class="panel-header">
          <span class="panel-icon">🔗</span>
          <h2 class="panel-title">URL 上传</h2>
        </div>
        <p class="panel-desc">支持 B站、抖音等视频平台链接</p>
        <div class="panel-body">
          <input
            v-model="urlInput"
            type="url"
            class="input"
            placeholder="粘贴视频链接，如 https://www.bilibili.com/video/BV1xx411c7mD"
            :disabled="urlLoading"
            @keyup.enter="submitUrl"
          />
          <button
            class="btn btn-primary"
            :disabled="urlLoading || !urlInput.trim()"
            @click="submitUrl"
          >
            <span v-if="urlLoading" class="spinner"></span>
            {{ urlLoading ? '提交中...' : '提交' }}
          </button>
        </div>
        <p v-if="urlError" class="error-msg">{{ urlError }}</p>
        <div v-if="urlResult" class="result-card">
          ✅ 任务已创建
          <span class="task-id">#{{ urlResult.taskId }}</span>
          <button class="btn btn-sm" @click="goToWorkbench">查看进度 →</button>
        </div>
      </div>

      <!-- 本地文件上传 -->
      <div class="panel">
        <div class="panel-header">
          <span class="panel-icon">📁</span>
          <h2 class="panel-title">本地视频上传</h2>
        </div>
        <p class="panel-desc">从本地上传视频文件</p>
        <div class="panel-body">
          <!-- 文件选择区 -->
          <div v-if="!selectedFile && !uploadProgress" class="file-dropzone" @click="fileInputRef?.click()">
            <span class="dropzone-icon">+</span>
            <span class="dropzone-text">点击选择视频文件</span>
            <span class="dropzone-hint">支持 mp4, avi, mov 等常见格式</span>
          </div>

          <!-- 已选文件（非上传中） -->
          <div v-else-if="selectedFile && !uploadProgress" class="file-selected">
            <span class="file-icon">🎞️</span>
            <div class="file-info">
              <span class="file-name">{{ selectedFile.name }}</span>
              <span class="file-size">{{ (selectedFile.size / 1024 / 1024).toFixed(1) }} MB</span>
            </div>
            <button class="btn-remove" @click="removeFile" :disabled="fileLoading">✕</button>
          </div>

          <!-- 上传进度 -->
          <div v-if="uploadProgress" class="upload-progress">
            <div class="file-selected" style="border: none; padding: 0 0 0.5rem 0; background: none;">
              <span class="file-icon">🎞️</span>
              <div class="file-info">
                <span class="file-name">{{ selectedFile?.name }}</span>
                <span class="file-size">{{ formatSize(uploadProgress.total) }}</span>
              </div>
            </div>

            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: uploadProgress.percent + '%' }"></div>
            </div>

            <div class="progress-info">
              <span class="progress-text">{{ uploadProgress.percent }}%</span>
              <span class="progress-detail">
                {{ formatSize(uploadProgress.loaded) }} / {{ formatSize(uploadProgress.total) }}
              </span>
              <span class="progress-speed">⚡ {{ uploadProgress.speed }}</span>
              <span class="progress-chunks">
                分片 {{ uploadProgress.chunkIndex + 1 }}/{{ uploadProgress.totalChunks }}
              </span>
            </div>

            <button class="btn btn-cancel" @click="cancelUpload" :disabled="!fileLoading">
              取消上传
            </button>
          </div>

          <input
            ref="fileInputRef"
            type="file"
            accept="video/*"
            class="hidden-input"
            @change="onFileSelected"
          />

          <!-- 上传按钮 -->
          <button
            v-if="selectedFile && !uploadProgress"
            class="btn btn-primary"
            :disabled="fileLoading"
            @click="uploadFile"
          >
            <span v-if="fileLoading" class="spinner"></span>
            {{ fileLoading ? '上传中...' : '上传' }}
          </button>
        </div>

        <p v-if="fileError" class="error-msg">{{ fileError }}</p>

        <div v-if="fileResult" class="result-card">
          <template v-if="fileResult.cached">
            📋 命中缓存，任务 <span class="task-id">#{{ fileResult.taskId }}</span> 已存在
          </template>
          <template v-else>
            ✅ 上传成功，任务 <span class="task-id">#{{ fileResult.taskId }}</span>
          </template>
          <button class="btn btn-sm" @click="goToWorkbench">查看结果 →</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.hero {
  text-align: center;
  margin-bottom: 2.5rem;
}

.hero-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: #1f2937;
  margin-bottom: 0.5rem;
}

.hero-desc {
  color: #6b7280;
  font-size: 0.95rem;
}

.upload-panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

@media (max-width: 768px) {
  .upload-panels {
    grid-template-columns: 1fr;
  }
}

.panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 1.5rem;
  transition: box-shadow 0.2s;
}

.panel:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.35rem;
}

.panel-icon {
  font-size: 1.4rem;
}

.panel-title {
  font-size: 1.1rem;
  font-weight: 600;
  color: #1f2937;
}

.panel-desc {
  color: #9ca3af;
  font-size: 0.85rem;
  margin-bottom: 1rem;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.input {
  width: 100%;
  padding: 0.7rem 0.9rem;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 0.9rem;
  outline: none;
  transition: border-color 0.2s;
  color: #1f2937;
}

.input:focus {
  border-color: #6366f1;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1);
}

.input:disabled {
  background: #f9fafb;
  cursor: not-allowed;
}

.file-dropzone {
  border: 2px dashed #d1d5db;
  border-radius: 8px;
  padding: 1.5rem;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.3rem;
}

.file-dropzone:hover {
  border-color: #6366f1;
  background: #f5f3ff;
}

.dropzone-icon {
  font-size: 1.5rem;
  color: #6366f1;
  font-weight: 300;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #e0e7ff;
  border-radius: 50%;
}

.dropzone-text {
  font-size: 0.9rem;
  font-weight: 500;
  color: #4f46e5;
}

.dropzone-hint {
  font-size: 0.8rem;
  color: #9ca3af;
}

.file-selected {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.7rem 0.9rem;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.file-icon {
  font-size: 1.3rem;
}

.file-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.file-name {
  font-size: 0.85rem;
  font-weight: 500;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 0.75rem;
  color: #9ca3af;
}

.btn-remove {
  background: none;
  border: none;
  color: #9ca3af;
  cursor: pointer;
  font-size: 1rem;
  padding: 0.25rem;
  line-height: 1;
}

.btn-remove:hover {
  color: #ef4444;
}

.hidden-input {
  display: none;
}

/* 进度条 UI */
.upload-progress {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 0.75rem;
}

.progress-bar {
  width: 100%;
  height: 8px;
  background: #e5e7eb;
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 0.5rem;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #6366f1, #8b5cf6);
  border-radius: 999px;
  transition: width 0.3s ease;
}

.progress-info {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  font-size: 0.8rem;
  color: #6b7280;
}

.progress-text {
  font-weight: 700;
  color: #6366f1;
  font-size: 0.9rem;
}

.progress-detail {
  color: #374151;
}

.progress-speed {
  color: #059669;
}

.progress-chunks {
  color: #9ca3af;
  font-size: 0.75rem;
}

/* 按钮 */
.btn {
  padding: 0.65rem 1.25rem;
  border: none;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
}

.btn-primary {
  background: #6366f1;
  color: #fff;
}

.btn-primary:hover:not(:disabled) {
  background: #4f46e5;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-sm {
  padding: 0.35rem 0.75rem;
  font-size: 0.8rem;
  background: #f3f4f6;
  color: #374151;
}

.btn-sm:hover {
  background: #e5e7eb;
}

.btn-cancel {
  width: 100%;
  padding: 0.5rem;
  font-size: 0.85rem;
  background: #fff;
  color: #ef4444;
  border: 1px solid #fecaca;
}

.btn-cancel:hover {
  background: #fef2f2;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-msg {
  color: #ef4444;
  font-size: 0.85rem;
  margin-top: 0.5rem;
}

.result-card {
  margin-top: 0.75rem;
  padding: 0.75rem 1rem;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 8px;
  font-size: 0.9rem;
  color: #065f46;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.task-id {
  font-weight: 700;
  color: #059669;
}
</style>
