<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { connectTv, getTvStatus, disconnectTv, getTvInfo, toastError } from '../api'

const form = reactive({
  ip: localStorage.getItem('tv.ip') || '',
  port: Number(localStorage.getItem('tv.port')) || 8080,
})
const loading = ref(false)
const connected = ref(false)
const info = ref(null)

/** 手动填写 IP/端口，由 Node 代理测试 GET /api/info */
async function onConnect() {
  if (!form.ip.trim()) return ElMessage.warning('请填写电视 IP')
  loading.value = true
  try {
    const r = await connectTv(form.ip.trim(), form.port)
    info.value = r.info
    connected.value = true
    localStorage.setItem('tv.ip', form.ip.trim())
    localStorage.setItem('tv.port', String(form.port))
    ElMessage.success('连接成功')
  } catch (e) {
    connected.value = false
    info.value = null
    toastError(e)
  } finally {
    loading.value = false
  }
}

async function onDisconnect() {
  await disconnectTv()
  connected.value = false
  info.value = null
}

// 刷新页面后恢复服务端内存中的连接状态
onMounted(async () => {
  try {
    const s = await getTvStatus()
    if (s.connected) {
      form.ip = s.tv.ip
      form.port = s.tv.port
      info.value = await getTvInfo()
      connected.value = true
    }
  } catch {
    connected.value = false
  }
})
</script>

<template>
  <el-card style="max-width: 640px; margin: 0 auto">
    <template #header><b>连接电视</b></template>
    <el-form :model="form" label-width="90px" @submit.prevent="onConnect">
      <el-form-item label="电视 IP">
        <el-input v-model="form.ip" placeholder="例如 192.168.1.100" clearable />
      </el-form-item>
      <el-form-item label="端口">
        <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" @click="onConnect">
          测试并连接
        </el-button>
        <el-button v-if="connected" @click="onDisconnect">断开</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="connected" type="success" :closable="false" title="已连接" show-icon style="margin-bottom: 12px" />
    <el-descriptions v-if="info" :column="1" border title="电视信息">
      <el-descriptions-item label="名称">{{ info.name }}</el-descriptions-item>
      <el-descriptions-item label="IP">{{ info.ip }}</el-descriptions-item>
      <el-descriptions-item label="端口">{{ info.port }}</el-descriptions-item>
      <el-descriptions-item label="版本">{{ info.version }}</el-descriptions-item>
    </el-descriptions>
    <el-button v-if="connected" type="primary" link style="margin-top: 12px" @click="$router.push('/push')">
      前往远程推送 →
    </el-button>
  </el-card>
</template>
