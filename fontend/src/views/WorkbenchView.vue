<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { authFetch } from '../utils/api'

const router = useRouter()

interface VideoTask {
  id: number
  status: string
  originalFilename: string
  sourceUrl: string
  progress: number
  duration: number | null
  summary: string
  transcript: string
  errorMessage: string
  createdAt: string
  updatedAt: string
}

const tasks = ref<VideoTask[]>([])
const loading = ref(true)
const error = ref('')
const selectedTask = ref<VideoTask | null>(null)
const showDetail = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const statusMap: Record<string, { label: string; color: string; bg: string }> = {
  PENDING:      { label: '等待中',     color: '#f59e0b', bg: '#fef3c7' },
  DOWNLOADING:  { label: '下载中',     color: '#3b82f6', bg: '#dbeafe' },
  TRANSCRIBING: { label: '转写中',     color: '#8b5cf6', bg: '#ede9fe' },
  SUMMARIZING:  { label: '摘要生成中', color: '#06b6d4', bg: '#cffafe' },
  SUCCESS:      { label: '已完成',     color: '#10b981', bg: '#d1fae5' },
  FAILED:       { label: '失败',       color: '#ef4444', bg: '#fee2e2' },
}

function getStatusInfo(status: string) {
  return statusMap[status] || { label: status, color: '#6b7280', bg: '#f3f4f6' }
}

function formatSize(text: string | null): string {
  if (!text) return '-'
  const kb = text.length / 1024
  if (kb > 1024) return (kb / 1024).toFixed(1) + ' MB'
  return kb.toFixed(0) + ' KB'
}

function formatDuration(sec: number | null): string {
  if (!sec) return '-'
  if (sec < 60) return sec + ' 秒'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m} 分 ${s} 秒`
}

function formatTime(dateStr: string | null): string {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function getDisplayName(task: VideoTask): string {
  if (task.originalFilename && !task.originalFilename.startsWith('http')) {
    return task.originalFilename
  }
  if (task.sourceUrl) {
    const u = new URL(task.sourceUrl)
    return u.hostname + u.pathname.substring(0, 30)
  }
  return `任务 #${task.id}`
}

async function fetchTasks() {
  try {
    error.value = ''
    const res = await authFetch('/api/v1/tasks/list')
    if (!res.ok) throw new Error('请求失败')
    const data = await res.json()
    tasks.value = data.data || []
  } catch (e: any) {
    error.value = '加载失败: ' + e.message
  } finally {
    loading.value = false
  }
}

function viewDetail(task: VideoTask) {
  selectedTask.value = task
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  selectedTask.value = null
}

function goDetail(taskId: number) {
  // 预留：跳转到详情页
}

onMounted(() => {
  fetchTasks()
  timer = setInterval(fetchTasks, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="workbench">
    <div class="page-header">
      <h1 class="page-title">📋 工作台</h1>
      <div class="page-actions">
        <span class="count-badge">共 {{ tasks.length }} 个任务</span>
        <button class="btn btn-refresh" @click="fetchTasks" :disabled="loading">
          {{ loading ? '刷新中...' : '🔄 刷新' }}
        </button>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading && tasks.length === 0" class="empty-state">
      <div class="spinner-lg"></div>
      <p>加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="error && tasks.length === 0" class="error-state">
      <p>{{ error }}</p>
      <button class="btn btn-primary" @click="fetchTasks">重试</button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="tasks.length === 0" class="empty-state">
      <span class="empty-icon">📭</span>
      <p class="empty-text">暂无任务</p>
      <p class="empty-hint">前往主页上传视频或提交 URL</p>
      <RouterLink to="/" class="btn btn-primary">去上传</RouterLink>
    </div>

    <!-- 任务列表 -->
    <div v-else class="task-table-wrapper">
      <table class="task-table">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th class="col-name">文件名 / 来源</th>
            <th class="col-status">状态</th>
            <th class="col-progress">进度</th>
            <th class="col-duration">用时</th>
            <th class="col-time">创建时间</th>
            <th class="col-action">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td class="col-id">
              <span class="task-id-badge">#{{ task.id }}</span>
            </td>
            <td class="col-name">
              <span class="task-name" :title="task.sourceUrl || task.originalFilename">
                {{ getDisplayName(task) }}
              </span>
            </td>
            <td class="col-status">
              <span
                class="status-badge"
                :style="{
                  color: getStatusInfo(task.status).color,
                  background: getStatusInfo(task.status).bg
                }"
              >
                {{ getStatusInfo(task.status).label }}
              </span>
            </td>
            <td class="col-progress">
              <div class="progress-bar-wrapper">
                <div
                  class="progress-bar-fill"
                  :style="{
                    width: task.progress + '%',
                    background: task.status === 'FAILED' ? '#ef4444' : '#6366f1'
                  }"
                ></div>
              </div>
              <span class="progress-text">{{ task.progress }}%</span>
            </td>
            <td class="col-duration">{{ formatDuration(task.duration) }}</td>
            <td class="col-time">{{ formatTime(task.createdAt) }}</td>
            <td class="col-action">
              <button class="btn btn-sm" @click="viewDetail(task)">查看</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 详情弹窗 -->
    <Teleport to="body">
      <div v-if="showDetail && selectedTask" class="modal-overlay" @click.self="closeDetail">
        <div class="modal">
          <div class="modal-header">
            <h2 class="modal-title">
              任务 #{{ selectedTask.id }} 详情
            </h2>
            <button class="modal-close" @click="closeDetail">✕</button>
          </div>
          <div class="modal-body">
            <div class="detail-section">
              <div class="detail-row">
                <span class="detail-label">状态</span>
                <span
                  class="status-badge"
                  :style="{
                    color: getStatusInfo(selectedTask.status).color,
                    background: getStatusInfo(selectedTask.status).bg
                  }"
                >
                  {{ getStatusInfo(selectedTask.status).label }}
                </span>
              </div>
              <div class="detail-row">
                <span class="detail-label">文件名</span>
                <span>{{ selectedTask.originalFilename || '-' }}</span>
              </div>
              <div class="detail-row" v-if="selectedTask.sourceUrl">
                <span class="detail-label">来源 URL</span>
                <span class="url-text">{{ selectedTask.sourceUrl }}</span>
              </div>
              <div class="detail-row">
                <span class="detail-label">进度</span>
                <span>{{ selectedTask.progress }}%</span>
              </div>
              <div class="detail-row">
                <span class="detail-label">用时</span>
                <span>{{ formatDuration(selectedTask.duration) }}</span>
              </div>
              <div class="detail-row" v-if="selectedTask.errorMessage">
                <span class="detail-label">错误信息</span>
                <span class="error-text">{{ selectedTask.errorMessage }}</span>
              </div>
              <div class="detail-row">
                <span class="detail-label">创建时间</span>
                <span>{{ formatTime(selectedTask.createdAt) }}</span>
              </div>
            </div>

            <div v-if="selectedTask.transcript" class="detail-section">
              <h3 class="section-title">📝 转写文本</h3>
              <pre class="text-block">{{ selectedTask.transcript.substring(0, 2000) }}{{ selectedTask.transcript.length > 2000 ? '...' : '' }}</pre>
              <p v-if="selectedTask.transcript.length > 2000" class="text-hint">仅显示前 2000 字符</p>
            </div>

            <div v-if="selectedTask.summary" class="detail-section">
              <h3 class="section-title">📄 AI 摘要</h3>
              <pre class="text-block summary-text">{{ selectedTask.summary }}</pre>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn" @click="closeDetail">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.workbench {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.5rem;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #1f2937;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.count-badge {
  font-size: 0.85rem;
  color: #6b7280;
  background: #f3f4f6;
  padding: 0.3rem 0.75rem;
  border-radius: 999px;
}

/* 按钮 */
.btn {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}

.btn-primary {
  background: #6366f1;
  color: #fff;
}

.btn-primary:hover {
  background: #4f46e5;
}

.btn-sm {
  padding: 0.3rem 0.7rem;
  font-size: 0.8rem;
  background: #f3f4f6;
  color: #374151;
}

.btn-sm:hover {
  background: #e5e7eb;
}

.btn-refresh {
  background: #f3f4f6;
  color: #374151;
}

.btn-refresh:hover:not(:disabled) {
  background: #e5e7eb;
}

.btn-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 空状态 / 错误 */
.empty-state, .error-state {
  text-align: center;
  padding: 4rem 1rem;
  color: #6b7280;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
}

.empty-icon {
  font-size: 3rem;
}

.empty-text {
  font-size: 1.1rem;
  font-weight: 600;
  color: #374151;
}

.empty-hint {
  font-size: 0.9rem;
  color: #9ca3af;
}

.spinner-lg {
  width: 32px;
  height: 32px;
  border: 3px solid #e5e7eb;
  border-top-color: #6366f1;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 表格 */
.task-table-wrapper {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: hidden;
}

.task-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
}

.task-table th {
  text-align: left;
  padding: 0.75rem 1rem;
  background: #f9fafb;
  color: #6b7280;
  font-weight: 600;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-bottom: 1px solid #e5e7eb;
}

.task-table td {
  padding: 0.85rem 1rem;
  border-bottom: 1px solid #f3f4f6;
  color: #374151;
}

.task-table tbody tr:hover {
  background: #f9fafb;
}

.task-table tbody tr:last-child td {
  border-bottom: none;
}

.col-id { width: 70px; }
.col-name { min-width: 150px; }
.col-status { width: 100px; }
.col-progress { width: 140px; }
.col-duration { width: 90px; }
.col-time { width: 145px; }
.col-action { width: 70px; }

.task-id-badge {
  color: #6366f1;
  font-weight: 600;
  font-size: 0.85rem;
}

.task-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 220px;
}

.status-badge {
  display: inline-block;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 500;
}

.progress-bar-wrapper {
  display: inline-block;
  width: 72px;
  height: 6px;
  background: #e5e7eb;
  border-radius: 999px;
  overflow: hidden;
  vertical-align: middle;
  margin-right: 0.5rem;
}

.progress-bar-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease;
}

.progress-text {
  font-size: 0.8rem;
  color: #6b7280;
  vertical-align: middle;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
  padding: 1rem;
}

.modal {
  background: #fff;
  border-radius: 16px;
  width: 100%;
  max-width: 680px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  animation: modalIn 0.2s ease;
}

@keyframes modalIn {
  from { opacity: 0; transform: scale(0.95) translateY(8px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid #e5e7eb;
}

.modal-title {
  font-size: 1.1rem;
  font-weight: 600;
  color: #1f2937;
}

.modal-close {
  background: none;
  border: none;
  font-size: 1.2rem;
  color: #9ca3af;
  cursor: pointer;
  padding: 0.25rem;
  line-height: 1;
}

.modal-close:hover {
  color: #374151;
}

.modal-body {
  padding: 1.5rem;
  overflow-y: auto;
  flex: 1;
}

.modal-footer {
  padding: 1rem 1.5rem;
  border-top: 1px solid #e5e7eb;
  display: flex;
  justify-content: flex-end;
}

.detail-section {
  margin-bottom: 1.25rem;
}

.detail-section:last-child {
  margin-bottom: 0;
}

.section-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 0.75rem;
}

.detail-row {
  display: flex;
  padding: 0.5rem 0;
  border-bottom: 1px solid #f9fafb;
  gap: 1rem;
}

.detail-label {
  width: 80px;
  flex-shrink: 0;
  color: #6b7280;
  font-size: 0.85rem;
}

.url-text {
  color: #6366f1;
  word-break: break-all;
  font-size: 0.85rem;
}

.error-text {
  color: #ef4444;
  font-size: 0.85rem;
}

.text-block {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 1rem;
  font-size: 0.82rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow-y: auto;
  color: #374151;
  font-family: inherit;
}

.summary-text {
  background: #f5f3ff;
  border-color: #e0e7ff;
  color: #1f2937;
}

.text-hint {
  font-size: 0.8rem;
  color: #9ca3af;
  margin-top: 0.4rem;
}
</style>
