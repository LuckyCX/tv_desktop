import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一请求实例：后端错误格式为 { error }，这里转成 Error.message
const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.response.use(
  (r) => r.data,
  (err) => {
    const msg = err.response?.data?.error || err.message || '请求失败'
    const e = new Error(msg)
    e.silent = err.config?.silent
    return Promise.reject(e)
  },
)

/** 显示错误提示并返回 undefined（用于页面层 catch） */
export function toastError(e) {
  ElMessage.error(e?.message || '操作失败')
}

export const getPcInfo = () => http.get('/pc-info')

export const connectTv = (ip, port) => http.post('/tv/connect', { ip, port }, { timeout: 15000 })
export const getTvStatus = () => http.get('/tv/status')
export const disconnectTv = () => http.post('/tv/disconnect')
export const getTvInfo = () => http.get('/tv/info')

export const getPushes = () => http.get('/tv/pushes')
export const installApk = (id) => http.post('/tv/install', { id }, { timeout: 130000 })
export const deletePush = (id) => http.post('/tv/delete', { id })
export const uninstallApp = (packageName) => http.post('/tv/uninstall', { packageName }, { timeout: 70000 })

/** 上传 APK 到 PC（再由 PC 转发到电视）。onProgress(percent) 为浏览器 -> PC 的进度 */
export function pushApk(file, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/tv/push', fd, {
    timeout: 0,
    onUploadProgress: (e) => e.total && onProgress?.(Math.floor((e.loaded / e.total) * 100)),
  })
}
export const getPushTask = (id) => http.get(`/tv/push/${id}`)
export const retryPushTask = (id) => http.post(`/tv/push/${id}/retry`)
export const removePushTask = (id) => http.delete(`/tv/push/${id}`)

export const getOtaInfo = () => http.get('/ota/info')
export function uploadOta(file, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/ota/upload', fd, {
    timeout: 0,
    onUploadProgress: (e) => e.total && onProgress?.(Math.floor((e.loaded / e.total) * 100)),
  })
}

export function formatSize(n) {
  if (n == null || isNaN(n)) return '-'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = Number(n)
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i ? 1 : 0)} ${u[i]}`
}

export function formatTime(t) {
  if (t == null || t === '') return '-'
  // 兼容秒/毫秒时间戳或字符串
  let d = typeof t === 'number' || /^\d+$/.test(String(t)) ? Number(t) : t
  if (typeof d === 'number' && d < 1e12) d *= 1000
  const date = new Date(d)
  return isNaN(date) ? String(t) : date.toLocaleString('zh-CN', { hour12: false })
}
