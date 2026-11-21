import { shallowRef } from 'vue'

export interface Actor {
  id: number
  username: string
  displayName: string
  patientId: number | null
  roles: string[]
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

export const actor = shallowRef<Actor | null>(null)
let csrf: { header: string; token: string } | undefined

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...options,
    headers: { Accept: 'application/json', ...options.headers },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    const fallback = response.status === 401 ? '请检查账号和密码，或重新登录。' : '操作失败，请检查页面信息。'
    throw new ApiError(response.status, problem.detail || fallback)
  }
  if (response.status === 204) return undefined as T
  return await response.json() as T
}

export async function mutate<T>(path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  csrf ??= await api('/session/csrf')
  const isForm = body instanceof FormData || body instanceof URLSearchParams
  return api<T>(path, {
    method: 'POST',
    body: isForm ? body : body === undefined ? undefined : JSON.stringify(body),
    headers: { [csrf!.header]: csrf!.token, ...(isForm ? {} : { 'Content-Type': 'application/json' }), ...headers },
  })
}

export async function loadSession() {
  try {
    actor.value = await api<Actor>('/session')
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) actor.value = null
    else throw error
  }
}

export async function login(username: string, password: string) {
  await mutate('/session/login', new URLSearchParams({ username, password }))
  csrf = undefined
  await loadSession()
}

export async function logout() {
  await mutate('/session/logout')
  csrf = undefined
  actor.value = null
}
