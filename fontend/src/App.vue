<script setup lang="ts">
import { ref, computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { isAuthenticated, getUser, logout } from './utils/api'

const route = useRoute()
const router = useRouter()

const user = computed(() => getUser())

function handleLogout() {
  logout()
  router.push('/login')
}
</script>

<template>
  <header class="navbar">
    <div class="navbar-inner">
      <RouterLink to="/" class="logo">
        <span class="logo-icon">🎬</span>
        <span class="logo-text">AiVideoSummary</span>
      </RouterLink>

      <div class="navbar-right">
        <nav class="nav-links">
          <RouterLink to="/" class="nav-link" :class="{ active: route.path === '/' }">
            🏠 主页
          </RouterLink>
          <RouterLink to="/workbench" class="nav-link" :class="{ active: route.path === '/workbench' }">
            📋 工作台
          </RouterLink>
        </nav>

        <div class="auth-area" v-if="isAuthenticated()">
          <span class="user-badge">👤 {{ user?.username }}</span>
          <button class="btn-logout" @click="handleLogout">退出</button>
        </div>
        <div class="auth-area" v-else>
          <RouterLink to="/login" class="nav-link">登录</RouterLink>
          <RouterLink to="/register" class="btn btn-primary btn-sm">注册</RouterLink>
        </div>
      </div>
    </div>
  </header>

  <main class="main-content">
    <RouterView />
  </main>
</template>

<style scoped>
.navbar {
  border-bottom: 1px solid #e5e7eb;
  position: sticky;
  top: 0;
  z-index: 100;
  backdrop-filter: blur(8px);
  background: rgba(255, 255, 255, 0.95);
}

.navbar-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 1.5rem;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  text-decoration: none;
  color: #1f2937;
  font-weight: 700;
  font-size: 1.15rem;
  flex-shrink: 0;
}

.logo-icon {
  font-size: 1.5rem;
}

.logo-text {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 1.5rem;
}

.nav-links {
  display: flex;
  gap: 0.25rem;
}

.nav-link {
  text-decoration: none;
  color: #6b7280;
  padding: 0.5rem 0.85rem;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 500;
  transition: all 0.2s;
}

.nav-link:hover {
  color: #1f2937;
  background: #f3f4f6;
}

.nav-link.active {
  color: #6366f1;
  background: #eef2ff;
}

.auth-area {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.user-badge {
  font-size: 0.85rem;
  color: #374151;
  font-weight: 500;
}

.btn-logout {
  background: none;
  border: 1px solid #e5e7eb;
  padding: 0.35rem 0.75rem;
  border-radius: 6px;
  font-size: 0.8rem;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-logout:hover {
  color: #ef4444;
  border-color: #fecaca;
  background: #fef2f2;
}

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
  text-decoration: none;
}

.btn-primary {
  background: #6366f1;
  color: #fff;
}

.btn-primary:hover {
  background: #4f46e5;
}

.btn-sm {
  padding: 0.35rem 0.85rem;
  font-size: 0.8rem;
}

.main-content {
  padding: 2rem 0;
}
</style>
