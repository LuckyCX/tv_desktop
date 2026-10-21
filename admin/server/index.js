/**
 * CleanYourTV 局域网管理后台 - Express 服务端
 * 说明：
 *  - 无登录，仅限局域网使用，默认监听 0.0.0.0:8787
 *  - 作为代理转发到电视端（避免浏览器跨域），并托管桌面 OTA 包
 *  - 推送 APK：浏览器 -> 本服务（落盘到 data/tmp）-> 流式 multipart 转发到电视，
 *    转发进度存内存，前端轮询 /api/tv/push/:id 获取，失败可重试
 */
import express from 'express'
import cors from 'cors'
import multer from 'multer'
import axios from 'axios'
import FormData from 'form-data'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const PORT = Number(process.env.PORT) || 8787
const OTA_DIR = path.join(ROOT, 'data', 'ota')
const TMP_DIR = path.join(ROOT, 'data', 'tmp')
const OTA_FILE_NAME = 'launcher.apk' // OTA 包固定文件名，对应 URL /ota/launcher.apk
const WEB_DIST = path.join(ROOT, 'web', 'dist')

fs.mkdirSync(OTA_DIR, { recursive: true })
fs.mkdirSync(TMP_DIR, { recursive: true })

// ---------- 工具函数 ----------

/** 获取局域网 IPv4：优先私网段（192.168 / 10 / 172.16-31），跳过虚拟网卡与回环 */
function detectLanIp() {
  const candidates = []
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const i of list || []) {
      if (i.family !== 'IPv4' || i.internal) continue
      let score = 0
      if (/^192\.168\./.test(i.address)) score = 3
      else if (/^10\./.test(i.address)) score = 2
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(i.address)) score = 1
      // 常见虚拟网卡（docker/vmware/utun 等）降权
      if (/^(docker|br-|veth|vmnet|vboxnet|utun|awdl|llw|bridge)/i.test(name)) score -= 5
      candidates.push({ address: i.address, score })
    }
  }
  candidates.sort((a, b) => b.score - a.score)
  return candidates[0]?.address || '127.0.0.1'
}

/** multer 会把文件名按 latin1 解析，这里还原为 utf8，避免中文文件名乱码 */
function fixName(name) {
  try {
    return Buffer.from(name, 'latin1').toString('utf8')
  } catch {
    return name
  }
}

/** 当前连接的电视（仅内存保存） */
let tv = null // { ip, port }

function tvBase() {
  if (!tv) {
    const e = new Error('尚未连接电视，请先到「连接」页填写电视 IP 与端口')
    e.status = 400
    throw e
  }
  return `http://${tv.ip}:${tv.port}`
}

/** 统一向电视发请求；proxy:false 避免系统代理干扰局域网访问 */
async function tvRequest(method, apiPath, { data, timeout = 15000, ...rest } = {}) {
  const url = tvBase() + apiPath
  try {
    const res = await axios.request({
      method,
      url,
      data,
      timeout,
      proxy: false,
      validateStatus: () => true,
      ...rest,
    })
    if (res.status >= 400) {
      const msg =
        (res.data && (res.data.error || res.data.message)) ||
        (typeof res.data === 'string' && res.data.slice(0, 200)) ||
        `电视返回 HTTP ${res.status}`
      const e = new Error(msg)
      e.status = 502
      throw e
    }
    return res.data
  } catch (err) {
    if (err.status) throw err
    const e = new Error(`无法连接电视 ${url}：${err.code || err.message}`)
    e.status = 502
    throw e
  }
}

/** 包装异步路由，统一错误响应 { error } */
const wrap = (fn) => (req, res) =>
  Promise.resolve(fn(req, res)).catch((err) => {
    console.error(`[${req.method} ${req.originalUrl}]`, err.message)
    res.status(err.status || 500).json({ error: err.message })
  })

// ---------- 应用 ----------

const app = express()
app.use(cors())
app.use(express.json())

// 获取本机信息 + OTA 地址
app.get('/api/pc-info', (req, res) => {
  const ip = detectLanIp()
  res.json({ ip, port: PORT, otaUrl: `http://${ip}:${PORT}/ota/${OTA_FILE_NAME}` })
})

// ---------- 电视连接 ----------

// 连接并测试：body {ip, port}
app.post(
  '/api/tv/connect',
  wrap(async (req, res) => {
    const ip = String(req.body?.ip || '').trim()
    const port = Number(req.body?.port)
    if (!ip || !/^[\w.\-]+$/.test(ip)) return res.status(400).json({ error: 'IP 格式不正确' })
    if (!Number.isInteger(port) || port < 1 || port > 65535)
      return res.status(400).json({ error: '端口格式不正确' })
    const prev = tv
    tv = { ip, port }
    try {
      const info = await tvRequest('get', '/api/info', { timeout: 5000 })
      res.json({ ok: true, tv, info })
    } catch (err) {
      tv = prev // 测试失败不覆盖旧连接
      throw err
    }
  }),
)

// 当前连接状态
app.get('/api/tv/status', (req, res) => res.json({ connected: !!tv, tv }))

// 断开
app.post('/api/tv/disconnect', (req, res) => {
  tv = null
  res.json({ ok: true })
})

// 代理电视 /api/info
app.get(
  '/api/tv/info',
  wrap(async (req, res) => res.json(await tvRequest('get', '/api/info', { timeout: 5000 }))),
)

// 代理电视已推送列表
app.get(
  '/api/tv/pushes',
  wrap(async (req, res) => res.json(await tvRequest('get', '/api/pushes'))),
)

// 代理 install / delete / uninstall
app.post(
  '/api/tv/install',
  wrap(async (req, res) => {
    if (req.body?.id == null) return res.status(400).json({ error: '缺少 id' })
    res.json(await tvRequest('post', '/api/install', { data: { id: req.body.id }, timeout: 120000 }))
  }),
)
app.post(
  '/api/tv/delete',
  wrap(async (req, res) => {
    if (req.body?.id == null) return res.status(400).json({ error: '缺少 id' })
    res.json(await tvRequest('post', '/api/delete', { data: { id: req.body.id } }))
  }),
)
app.post(
  '/api/tv/uninstall',
  wrap(async (req, res) => {
    if (!req.body?.packageName) return res.status(400).json({ error: '缺少 packageName' })
    res.json(
      await tvRequest('post', '/api/uninstall', {
        data: { packageName: req.body.packageName },
        timeout: 60000,
      }),
    )
  }),
)

// ---------- APK 推送（带进度） ----------

/**
 * 推送任务：id -> { id, name, size, tmpPath, status, sent, error, startedAt }
 * status: uploading(转发中) | success | failed
 * 失败时保留临时文件以便重试；成功后删除
 */
const tasks = new Map()
const upload = multer({
  storage: multer.diskStorage({
    destination: TMP_DIR,
    filename: (req, file, cb) => cb(null, crypto.randomUUID() + '.part'),
  }),
})

const publicTask = (t) => ({
  id: t.id,
  name: t.name,
  size: t.size,
  status: t.status,
  sent: t.sent,
  percent: t.size ? Math.min(100, Math.floor((t.sent / t.size) * 100)) : 0,
  error: t.error,
  tvResult: t.tvResult,
})

/** 将任务文件流式 multipart 转发到电视 POST /api/push */
async function forwardTask(task) {
  task.status = 'uploading'
  task.sent = 0
  task.error = null
  try {
    const base = tvBase()
    const form = new FormData()
    const stream = fs.createReadStream(task.tmpPath)
    stream.on('data', (chunk) => (task.sent += chunk.length)) // 按已读取字节估算转发进度
    form.append('file', stream, { filename: task.name, contentType: 'application/vnd.android.package-archive', knownLength: task.size })
    const length = await new Promise((resolve, reject) =>
      form.getLength((e, l) => (e ? reject(e) : resolve(l))),
    )
    const res = await axios.post(`${base}/api/push`, form, {
      headers: { ...form.getHeaders(), 'Content-Length': length },
      maxBodyLength: Infinity,
      maxContentLength: Infinity,
      timeout: 0,
      proxy: false,
      validateStatus: () => true,
    })
    if (res.status >= 400) {
      throw new Error(
        (res.data && (res.data.error || res.data.message)) || `电视返回 HTTP ${res.status}`,
      )
    }
    task.sent = task.size
    task.status = 'success'
    task.tvResult = res.data
    fs.rm(task.tmpPath, { force: true }, () => {})
  } catch (err) {
    task.status = 'failed'
    task.error = err.code ? `${err.code}: ${err.message}` : err.message
    console.error(`[push ${task.name}] 失败:`, task.error)
  }
}

// 接收浏览器上传并立即开始转发；返回任务信息，前端轮询进度
app.post('/api/tv/push', (req, res) => {
  if (!tv) return res.status(400).json({ error: '尚未连接电视，请先到「连接」页填写电视 IP 与端口' })
  upload.single('file')(req, res, (err) => {
    if (err) return res.status(400).json({ error: '接收文件失败：' + err.message })
    if (!req.file) return res.status(400).json({ error: '未收到文件（字段名需为 file）' })
    const task = {
      id: crypto.randomUUID(),
      name: fixName(req.file.originalname),
      size: req.file.size,
      tmpPath: req.file.path,
      status: 'uploading',
      sent: 0,
      error: null,
    }
    tasks.set(task.id, task)
    forwardTask(task)
    res.json(publicTask(task))
  })
})

// 查询任务进度
app.get('/api/tv/push/:id', (req, res) => {
  const t = tasks.get(req.params.id)
  if (!t) return res.status(404).json({ error: '任务不存在' })
  res.json(publicTask(t))
})

// 失败后重试（复用服务器上的临时文件，无需浏览器重新上传）
app.post('/api/tv/push/:id/retry', (req, res) => {
  const t = tasks.get(req.params.id)
  if (!t) return res.status(404).json({ error: '任务不存在' })
  if (t.status === 'uploading') return res.status(409).json({ error: '任务正在进行' })
  if (t.status === 'success') return res.status(409).json({ error: '任务已成功' })
  if (!fs.existsSync(t.tmpPath)) return res.status(410).json({ error: '临时文件已丢失，请重新选择文件' })
  forwardTask(t)
  res.json(publicTask(t))
})

// 放弃任务并清理临时文件
app.delete('/api/tv/push/:id', (req, res) => {
  const t = tasks.get(req.params.id)
  if (t) {
    fs.rm(t.tmpPath, { force: true }, () => {})
    tasks.delete(t.id)
  }
  res.json({ ok: true })
})

// ---------- 桌面 OTA 包 ----------

const otaUpload = multer({
  storage: multer.diskStorage({
    destination: TMP_DIR,
    filename: (req, file, cb) => cb(null, crypto.randomUUID() + '.ota'),
  }),
})

function otaInfo() {
  const file = path.join(OTA_DIR, OTA_FILE_NAME)
  if (!fs.existsSync(file)) return { exists: false }
  const st = fs.statSync(file)
  return { exists: true, name: OTA_FILE_NAME, size: st.size, mtime: st.mtimeMs }
}

app.get('/api/ota/info', (req, res) => {
  const ip = detectLanIp()
  res.json({ ...otaInfo(), otaUrl: `http://${ip}:${PORT}/ota/${OTA_FILE_NAME}` })
})

// 上传 launcher APK，固定保存为 data/ota/launcher.apk（覆盖旧版本）
app.post('/api/ota/upload', (req, res) => {
  otaUpload.single('file')(req, res, (err) => {
    if (err) return res.status(400).json({ error: '接收文件失败：' + err.message })
    if (!req.file) return res.status(400).json({ error: '未收到文件（字段名需为 file）' })
    if (!/\.apk$/i.test(fixName(req.file.originalname))) {
      fs.rm(req.file.path, { force: true }, () => {})
      return res.status(400).json({ error: '仅支持 .apk 文件' })
    }
    try {
      // 先落临时文件再 rename，避免电视下载到写了一半的文件
      fs.renameSync(req.file.path, path.join(OTA_DIR, OTA_FILE_NAME))
    } catch (e) {
      // 跨设备 rename 失败时退化为复制
      fs.copyFileSync(req.file.path, path.join(OTA_DIR, OTA_FILE_NAME))
      fs.rmSync(req.file.path, { force: true })
    }
    const ip = detectLanIp()
    res.json({ ...otaInfo(), otaUrl: `http://${ip}:${PORT}/ota/${OTA_FILE_NAME}` })
  })
})

// 静态下载：GET /ota/:file
app.use(
  '/ota',
  express.static(OTA_DIR, {
    index: false,
    dotfiles: 'deny',
    setHeaders: (res) => res.setHeader('Content-Type', 'application/vnd.android.package-archive'),
  }),
)

// ---------- 生产模式：托管前端构建产物 ----------
if (fs.existsSync(WEB_DIST)) {
  app.use(express.static(WEB_DIST))
  app.get(/^\/(?!api\/|ota\/).*/, (req, res) => res.sendFile(path.join(WEB_DIST, 'index.html')))
}

app.listen(PORT, '0.0.0.0', () => {
  const ip = detectLanIp()
  console.log(`CleanYourTV Admin 已启动: http://localhost:${PORT}  局域网: http://${ip}:${PORT}`)
  console.log(`OTA 地址: http://${ip}:${PORT}/ota/${OTA_FILE_NAME}`)
})
