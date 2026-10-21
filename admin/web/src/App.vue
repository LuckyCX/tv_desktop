<script setup>
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <div class="brand">CleanYourTV 管理后台</div>
      <el-menu mode="horizontal" :default-active="route.path" router :ellipsis="false" class="menu">
        <el-menu-item index="/connect"><el-icon><Connection /></el-icon>连接电视</el-menu-item>
        <el-menu-item index="/push"><el-icon><UploadFilled /></el-icon>远程推送</el-menu-item>
        <el-menu-item index="/ota"><el-icon><Box /></el-icon>桌面 OTA 包</el-menu-item>
      </el-menu>
    </header>
    <main class="content" :class="{ 'is-push': route.name === 'push' }">
      <router-view />
    </main>
  </div>
</template>

<style>
html,
body,
#app {
  margin: 0;
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.topbar {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 24px;
}
.brand {
  font-size: 18px;
  font-weight: 700;
  margin-right: 32px;
  color: #303133;
}
.menu {
  flex: 1;
  border-bottom: none !important;
}
.content {
  flex: 1;
  padding: 24px;
}
/* 远程推送页：蓝色背景 + 白色卡片 */
.content.is-push {
  background: linear-gradient(160deg, #1d4ed8 0%, #2563eb 50%, #3b82f6 100%);
}
</style>
