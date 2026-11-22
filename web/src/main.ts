import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import SignIn from './SignIn.vue'
import Workspace from './Workspace.vue'
import { actor, loadSession } from './api'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 20 }
    if (to.path !== from.path || (from.hash && !Object.keys(to.query).length)) return { top: 0 }
  },
  routes: [
    { path: '/', component: SignIn },
    { path: '/workspace', component: Workspace },
  ],
})

router.beforeEach((to) => {
  if (to.path === '/workspace' && !actor.value) return '/'
  if (to.path === '/' && actor.value) return '/workspace'
})

async function start() {
  try {
    await loadSession()
  } catch {
    // Keep the sign-in screen available so it can report a connection error.
  }
  createApp(App).use(router).mount('#app')
}

void start()
