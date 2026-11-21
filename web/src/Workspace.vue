<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { actor, logout } from './api'
import SchedulingView from './SchedulingView.vue'
import InboxView from './InboxView.vue'
import FollowupView from './FollowupView.vue'

const router = useRouter()
const appointmentChange = ref(0)
const followupChange = ref(0)
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
    <SchedulingView v-if="actor.roles.some((role) => ['PATIENT', 'DOCTOR', 'OPERATIONS'].includes(role))" :actor="actor" :refresh-version="followupChange" @changed="appointmentChange++" />
    <FollowupView v-if="actor.roles.some((role) => ['PATIENT', 'FOLLOWUP', 'REVIEWER'].includes(role))" :actor="actor" :refresh-version="appointmentChange" @booked="followupChange++" />
    <InboxView :actor="actor" />
  </main>
</template>
