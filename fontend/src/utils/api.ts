const BASE_URL = ''

function getToken(): string | null {
  return localStorage.getItem('token')
}

export function setToken(token: string) {
  localStorage.setItem('token', token)
}

export function clearToken() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
}

export function saveUser(user: { id: number; username: string }) {
  localStorage.setItem('user', JSON.stringify(user))
}

export function getUser(): { id: number; username: string } | null {
  const raw = localStorage.getItem('user')
  return raw ? JSON.parse(raw) : null
}

export function isAuthenticated(): boolean {
  return !!getToken()
}

/**
 * 带认证的 fetch 封装，自动附带 Authorization header
 */
export function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const token = getToken()
  const headers: Record<string, string> = {
    ...(options.headers as Record<string, string> || {}),
  }

  // 不要为 FormData 设置 Content-Type（浏览器会自动设置 multipart boundary）
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json'
  }

  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  return fetch(BASE_URL + url, { ...options, headers }).then(async (res) => {
    // 401 时清除 token（后端 token 过期或无效）
    if (res.status === 401) {
      clearToken()
      // 跳转到登录页（仅在浏览器环境）
      if (typeof window !== 'undefined') {
        const currentPath = window.location.pathname + window.location.search
        if (currentPath !== '/login' && currentPath !== '/register') {
          window.location.href = `/login?redirect=${encodeURIComponent(currentPath)}`
        }
      }
    }
    return res
  })
}

/**
 * 发起登录请求
 */
export async function login(username: string, password: string) {
  const res = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data = await res.json()
  if (!res.ok) throw new Error(data.error || '登录失败')
  setToken(data.token)
  saveUser(data.user)
  return data
}

/**
 * 发起注册请求
 */
export async function register(username: string, password: string) {
  const res = await fetch(`${BASE_URL}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data = await res.json()
  if (!res.ok) throw new Error(data.error || '注册失败')
  setToken(data.token)
  saveUser(data.user)
  return data
}

/**
 * 退出登录
 */
export function logout() {
  clearToken()
  if (typeof window !== 'undefined') {
    window.location.href = '/login'
  }
}
