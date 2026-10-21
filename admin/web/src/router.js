import { createRouter, createWebHashHistory } from 'vue-router'
import Connect from './views/Connect.vue'
import Push from './views/Push.vue'
import Ota from './views/Ota.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/connect' },
    { path: '/connect', name: 'connect', component: Connect, meta: { title: '连接电视' } },
    { path: '/push', name: 'push', component: Push, meta: { title: '远程推送' } },
    { path: '/ota', name: 'ota', component: Ota, meta: { title: '桌面 OTA 包' } },
  ],
})
