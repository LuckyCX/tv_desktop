<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTvStatus,
  pushApk,
  getPushTask,
  retryPushTask,
  removePushTask,
  getPushes,
  installApk,
  deletePush,
  uninstallApp,
  formatSize,
  formatTime,
  toastError,
} from '../api'

const connected = ref(true)
const tasks = ref([]) // 推送任务列表
const pushes = ref([])
const listLoading = ref(false)
const busyIds = reactive({}) // 行内按钮 loading

let queue = Promise.resolve() // 串行队列：一个文件推完再推下一个，避免占满电视带宽
const timers = new Set()

onBeforeUnmount(() => timers.forEach(clearInterval))

/**
 * 任务阶段：
 *  upload  浏览器 -> PC
 *  push    PC -> 电视（轮询服务端进度）
 *  success / failed
 */
function addFiles(files) {
  for (const f of files) {
    if (!/\.apk$/i.test(f.name)) {
      ElMessage.warning(`${f.name} 不是 APK，已忽略`)
      continue
    }
    const task = reactive({
      key: Math.random().toString(36).slice(2),
      file: f,
      name: f.name,
      size: f.size,
      phase: 'waiting',
      upPercent: 0,
      tvPercent: 0,
      serverId: null,
      error: '',
    })
    tasks.value.unshift(task)
    queue = queue.then(() => runTask(task))
  }
}

/** 轮询服务端任务直到结束 */
function waitServerTask(task) {
  return new Promise((resolve) => {
    task.phase = 'push'
    const timer = setInterval(async () => {
      try {
        const s = await getPushTask(task.serverId)
        task.tvPercent = s.percent
        if (s.status === 'success') {
          task.tvPercent = 100
          task.phase = 'success'
        } else if (s.status === 'failed') {
          task.phase = 'failed'
          task.error = s.error || '推送失败'
        } else return
      } catch (e) {
        task.phase = 'failed'
        task.error = e.message
      }
      clearInterval(timer)
      timers.delete(timer)
      resolve()
    }, 500)
    timers.add(timer)
  })
}

async function runTask(task) {
  task.error = ''
  try {
    if (!task.serverId) {
      task.phase = 'upload'
      task.upPercent = 0
      const s = await pushApk(task.file, (p) => (task.upPercent = p))
      task.upPercent = 100
      task.serverId = s.id
    } else {
      await retryPushTask(task.serverId) // 服务端已有临时文件，直接重试转发
    }
    await waitServerTask(task)
    if (task.phase === 'success') loadPushes()
  } catch (e) {
    task.phase = 'failed'
    task.error = e.message
  }
}

function retry(task) {
  task.phase = 'waiting'
  task.tvPercent = 0
  queue = queue.then(() => runTask(task))
}

async function removeTask(task) {
  if (task.serverId && task.phase !== 'success') removePushTask(task.serverId).catch(() => {})
  tasks.value = tasks.value.filter((t) => t !== task)
}

// el-upload 自定义行为：不使用其内置上传，只收集文件
function onChange(_file, fileList) {
  const raw = fileList.map((f) => f.raw).filter(Boolean)
  addFiles(raw)
  upload.value?.clearFiles()
}
const upload = ref(null)

const statusText = { waiting: '排队中', upload: '上传到电脑', push: '推送到电视', success: '推送成功', failed: '失败' }
const statusType = { waiting: 'info', upload: 'primary', push: 'primary', success: 'success', failed: 'danger' }

// ---- 已推送列表 ----
async function loadPushes() {
  listLoading.value = true
  try {
    pushes.value = await getPushes()
  } catch (e) {
    toastError(e)
  } finally {
    listLoading.value = false
  }
}

async function act(row, key, fn, okMsg) {
  busyIds[row.id + key] = true
  try {
    await fn()
    ElMessage.success(okMsg)
    return true
  } catch (e) {
    toastError(e)
  } finally {
    busyIds[row.id + key] = false
  }
}

const onInstall = (row) => act(row, 'i', () => installApk(row.id), '已发送安装指令')

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」？`, '删除', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  if (await act(row, 'd', () => deletePush(row.id), '已删除')) loadPushes()
}

async function onUninstall(row) {
  // 电视列表不含包名，需要手动填写
  let packageName
  try {
    const r = await ElMessageBox.prompt('请输入要卸载的应用包名', '卸载应用', {
      inputPlaceholder: 'com.example.app',
      inputPattern: /^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$/,
      inputErrorMessage: '包名格式不正确',
    })
    packageName = r.value
  } catch {
    return
  }
  act(row, 'u', () => uninstallApp(packageName), '已发送卸载指令')
}

onMounted(async () => {
  try {
    connected.value = (await getTvStatus()).connected
  } catch {
    connected.value = false
  }
  if (connected.value) loadPushes()
})
</script>

<template>
  <div class="wrap">
    <el-alert
      v-if="!connected"
      type="warning"
      show-icon
      :closable="false"
      title="尚未连接电视，请先到「连接电视」页面填写 IP 和端口"
      style="margin-bottom: 16px"
    />

    <el-card class="card" shadow="never">
      <template #header><b>推送 APK 到电视</b></template>
      <el-upload
        ref="upload"
        drag
        multiple
        accept=".apk"
        :auto-upload="false"
        :show-file-list="false"
        :disabled="!connected"
        :on-change="onChange"
      >
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">将 APK 拖到此处，或 <em>点击选择</em>（支持多选）</div>
      </el-upload>

      <div v-for="t in tasks" :key="t.key" class="task">
        <div class="task-head">
          <span class="task-name">{{ t.name }}</span>
          <span class="muted">{{ formatSize(t.size) }}</span>
          <el-tag :type="statusType[t.phase]" size="small">{{ statusText[t.phase] }}</el-tag>
          <span style="flex: 1" />
          <el-button v-if="t.phase === 'failed'" size="small" type="primary" @click="retry(t)">重试</el-button>
          <el-button size="small" link @click="removeTask(t)">移除</el-button>
        </div>
        <div class="bar-row">
          <span class="bar-label">上传到电脑</span>
          <el-progress :percentage="t.upPercent" :status="t.upPercent === 100 ? 'success' : ''" />
        </div>
        <div class="bar-row">
          <span class="bar-label">推送到电视</span>
          <el-progress
            :percentage="t.tvPercent"
            :status="t.phase === 'failed' ? 'exception' : t.phase === 'success' ? 'success' : ''"
          />
        </div>
        <div v-if="t.error" class="err">{{ t.error }}</div>
      </div>
    </el-card>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <b>电视已推送列表</b>
          <el-button size="small" :loading="listLoading" :disabled="!connected" @click="loadPushes">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </template>
      <el-table :data="pushes" v-loading="listLoading" empty-text="暂无推送记录" stripe>
        <el-table-column prop="name" label="文件名" min-width="180" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.time) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" :loading="busyIds[row.id + 'i']" @click="onInstall(row)">安装</el-button>
            <el-button size="small" type="danger" plain :loading="busyIds[row.id + 'd']" @click="onDelete(row)">删除</el-button>
            <el-button size="small" :loading="busyIds[row.id + 'u']" @click="onUninstall(row)">卸载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.wrap {
  max-width: 960px;
  margin: 0 auto;
}
.card {
  background: #fff;
  border-radius: 12px;
  border: none;
}
.task {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.task-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.task-name {
  font-weight: 600;
  word-break: break-all;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-label {
  width: 80px;
  font-size: 12px;
  color: #606266;
  flex: none;
}
.bar-row :deep(.el-progress) {
  flex: 1;
}
.err {
  margin-top: 6px;
  color: #f56c6c;
  font-size: 13px;
  word-break: break-all;
}
</style>
