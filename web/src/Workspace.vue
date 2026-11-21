<script setup lang="ts">
import { useRouter } from 'vue-router'
import { actor, logout } from './api'

const router = useRouter()
const roleNames: Record<string, string> = { PATIENT: '患者', DOCTOR: '接诊医生', FOLLOWUP: '随访人员', OPERATIONS: '运营', REVIEWER: '审核员' }

async function signOut() {
  await logout()
  await router.replace('/')
}
</script>

<template>
  <main v-if="actor" class="workspace">
    <div class="workspace-heading">
      <div><p>{{ actor.roles.map((role) => roleNames[role]).join(' · ') }}</p><h1>{{ actor.displayName }}</h1></div>
      <button type="button" class="secondary" @click="signOut">退出登录</button>
    </div>
  </main>
</template>
