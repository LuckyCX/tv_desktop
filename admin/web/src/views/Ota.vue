<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getOtaInfo, uploadOta, formatSize, formatTime, toastError } from '../api'

const info = ref({ exists: false })
const percent = ref(0)
const uploading = ref(false)
const upload = ref(null)

async function load() {
  try {
    info.value = await getOtaInfo()
  } catch (e) {
    toastError(e)
  }
}

/** 选择文件后立即上传，覆盖 data/ota/launcher.apk */
async function onChange(file) {
  if (!file.raw) return
  if (!/\.apk$/i.test(file.name)) {
    upload.value?.clearFiles()
    return ElMessage.warning('仅支持 .apk 文件')
  }
  uploading.value = true
  percent.value = 0
  try {
    info.value = await uploadOta(file.raw, (p) => (percent.value = p))
    percent.value = 100
    ElMessage.success('上传成功')
  } catch (e) {
    toastError(e)
  } finally {
    uploading.value = false
    upload.value?.clearFiles()
  }
}

async function copy() {
  const text = info.value.otaUrl
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 非 https / 局域网 IP 访问时 clipboard 不可用，退化为 execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  ElMessage.success('已复制')
}

onMounted(load)
</script>

<template>
  <el-card style="max-width: 720px; margin: 0 auto">
    <template #header><b>桌面 OTA 包</b></template>

    <el-upload
      ref="upload"
      drag
      accept=".apk"
      :auto-upload="false"
      :show-file-list="false"
      :disabled="uploading"
      :on-change="onChange"
    >
      <el-icon class="el-icon--upload"><upload-filled /></el-icon>
      <div class="el-upload__text">拖入 launcher APK 或 <em>点击选择</em>（将覆盖 data/ota/launcher.apk）</div>
    </el-upload>
    <el-progress v-if="uploading || percent" :percentage="percent" style="margin-top: 12px" />

    <el-divider />

    <el-descriptions :column="1" border>
      <el-descriptions-item label="当前包">
        <template v-if="info.exists">
          {{ info.name }}（{{ formatSize(info.size) }}，更新于 {{ formatTime(info.mtime) }}）
        </template>
        <el-tag v-else type="info">尚未上传</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="更新地址">
        <div style="display: flex; gap: 8px">
          <el-input :model-value="info.otaUrl" readonly />
          <el-button type="primary" :disabled="!info.otaUrl" @click="copy">复制</el-button>
        </div>
      </el-descriptions-item>
    </el-descriptions>
    <p style="color: #909399; font-size: 13px">
      地址中的 IP 为本机自动检测的局域网 IP，电视需与电脑处于同一局域网。
    </p>
  </el-card>
</template>
