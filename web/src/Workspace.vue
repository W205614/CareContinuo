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
const doctor = computed(() => actor.value?.roles.includes('DOCTOR'))
const operations = computed(() => actor.value?.roles.includes('OPERATIONS'))
const followup = computed(() => actor.value?.roles.some(role => ['PATIENT', 'FOLLOWUP', 'REVIEWER'].includes(role)))
const admin = computed(() => actor.value?.roles.includes('ADMIN'))

const sections = computed(() => [
  ...(patient.value ? [
    { id: 'patient-home-title', label: '待处理事项' },
    { id: 'schedule-workspace', label: '预约挂号' },
    { id: 'appointment-title', label: '我的预约' },
  ] : [{ id: 'inbox-title', label: '工作待办' }]),
  ...(operations.value ? [{ id: 'manage-title', label: '排班与放号' }] : []),
  ...(doctor.value && !patient.value ? [{ id: 'appointment-title', label: '接诊列表' }] : []),
  ...(followup.value ? [{ id: 'followup-title', label: patient.value ? '我的随访' : '随访工作台' }] : []),
  ...(admin.value ? [{ id: 'staff-admin-heading', label: '科室与人员管理' }] : []),
  ...(patient.value ? [{ id: 'inbox-title', label: '站内消息' }] : []),
])
const currentSection = computed(() => route.hash.slice(1) || (route.fullPath === '/workspace' ? sections.value[0]?.id : undefined))

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
      <div>
        <h1>{{ actor.displayName }}</h1>
        <p>{{ actor.roles.map((role) => roleNames[role]).join(' · ') }}</p>
      </div>
      <button type="button" class="secondary" @click="signOut">退出登录</button>
    </div>
    <div class="workspace-grid">
      <nav class="workspace-nav" aria-label="工作台导航">
        <RouterLink v-for="section in sections" :key="section.id"
          :to="{ query: route.query, hash: '#' + section.id }" :aria-current="currentSection === section.id ? 'location' : undefined">
          {{ section.label }}
        </RouterLink>
      </nav>
      <div class="workspace-body">
        <PatientHome v-if="patient" :refresh-version="activity + inboxChange" @read-reply="inboxChange++" />
        <InboxView v-if="!patient" :actor="actor" :refresh-version="activity" />
        <SchedulingView v-if="patient || doctor || operations"
          :actor="actor" :refresh-version="followupChange" :appointment-id="target('appointment')" @changed="appointmentChange++" />
        <FollowupView v-if="followup"
          :actor="actor" :refresh-version="appointmentChange" :task-id="target('followup')" :request-id="target('request')"
          :revisit-appointment-id="target('revisitAppointment')" :progress-target="route.query.progress === 'result'"
          :episode-target="target('episode')" @booked="followupChange++" @changed="followupChange++" />
        <AdminView v-if="admin" />
        <InboxView v-if="patient" :actor="actor" :refresh-version="activity + inboxChange" @read-message="inboxChange++" />
      </div>
    </div>
  </main>
</template>
