<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { actor, logout } from './api'
import SchedulingView from './SchedulingView.vue'
import InboxView from './InboxView.vue'
import FollowupView from './FollowupView.vue'
import AdminView from './AdminView.vue'
import PatientHome from './PatientHome.vue'

const router = useRouter()
const route = useRoute()
const appointmentChange = ref(0)
const followupChange = ref(0)
const activity = computed(() => appointmentChange.value + followupChange.value)
const inboxChange = ref(0)
const patient = computed(() => actor.value?.roles.includes('PATIENT'))
function target(key: string) {
  const id = Number(route.query[key])
  return Number.isSafeInteger(id) && id > 0 ? id : undefined
}
const roleNames: Record<string, string> = { PATIENT: '患者', DOCTOR: '接诊医生', FOLLOWUP: '随访人员', OPERATIONS: '运营', REVIEWER: '审核员', ADMIN: '管理员' }

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
    <PatientHome v-if="patient" :refresh-version="activity + inboxChange" @read-reply="inboxChange++" />
    <InboxView v-if="!patient" :actor="actor" :refresh-version="activity" />
    <SchedulingView v-if="actor.roles.some((role) => ['PATIENT', 'DOCTOR', 'OPERATIONS'].includes(role))"
      :actor="actor" :refresh-version="followupChange" :appointment-id="target('appointment')" @changed="appointmentChange++" />
    <FollowupView v-if="actor.roles.some((role) => ['PATIENT', 'FOLLOWUP', 'REVIEWER'].includes(role))"
      :actor="actor" :refresh-version="appointmentChange" :task-id="target('followup')" :request-id="target('request')"
      :revisit-appointment-id="target('revisitAppointment')" :progress-target="route.query.progress === 'result'"
      :episode-target="target('episode')" @booked="followupChange++" @changed="followupChange++" />
    <AdminView v-if="actor.roles.includes('ADMIN')" />
    <InboxView v-if="patient" :actor="actor" :refresh-version="activity + inboxChange" @read-message="inboxChange++" />
  </main>
</template>
