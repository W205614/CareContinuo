<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, mutate } from './api'
import { messageTarget, type InboxMessage } from './notifications'
import type { Appointment } from './appointments'
import type { Followup } from './followup'
import { shanghaiTime } from './time'

const props = defineProps<{ refreshVersion: number }>()
const emit = defineEmits<{ readReply: [] }>()
const route = useRoute()
const router = useRouter()
const appointments = ref<Appointment[]>([])
const highlights = ref<InboxMessage[]>([])
const replies = computed(() => highlights.value.filter(message => message.revisitRequestId !== null))
const progress = computed(() => highlights.value.filter(message => message.revisitRequestId === null))
const openingMessage = ref(false)
const followups = ref<Followup[]>([])
const expanded = ref(false)
const showAllProgress = ref(false)
const loading = ref(true)
const error = ref('')

const appointmentTitles: Record<string, string> = {
  STOPPED: '预约停诊', CHECKED_IN: '已签到，等待接诊', BOOKED: '即将到诊',
}
const items = computed(() => [
  ...appointments.value.map((item) => ({
    id: item.id, type: 'appointment', urgent: item.status === 'STOPPED', at: item.startsAt,
    title: appointmentTitles[item.status] + ' · ' + item.department + ' · ' + item.doctor,
    label: item.status === 'STOPPED' ? '处理停诊' : '查看预约',
    timeLabel: item.status === 'STOPPED' ? '原开诊时间' : '开诊时间',
  })),
  ...followups.value.map((item) => ({
    id: item.id, type: 'followup', urgent: item.needsReview, at: item.dueAt,
    title: (item.needsReview ? '安排更正待确认' : '待完成随访') + ' · ' + item.title,
    label: '查看随访安排', timeLabel: item.needsReview ? '原完成期限' : '完成期限',
  })),
].sort((a, b) => Date.parse(a.at) - Date.parse(b.at)))
const urgentItems = computed(() => items.value.filter(item => item.urgent))
const ordinaryItems = computed(() => items.value.filter(item => !item.urgent))

async function refresh() {
  error.value = ''
  try {
    const [visits, tasks, unread] = await Promise.all([
      api<Appointment[]>('/appointments?actionable=true'), api<Followup[]>('/followups?actionable=true'),
      api<InboxMessage[]>('/inbox/highlights'),
    ])
    appointments.value = visits
    followups.value = tasks
    highlights.value = unread
  } catch (failure) {
    error.value = (failure as Error).message
  } finally { loading.value = false }
}

async function openMessage(message: InboxMessage) {
  openingMessage.value = true
  error.value = ''
  try {
    await mutate(`/inbox/${message.id}/read`)
    highlights.value = highlights.value.filter(item => item.id !== message.id)
    await router.push(messageTarget(message))
    emit('readReply')
  } catch (failure) { error.value = (failure as Error).message }
  finally { openingMessage.value = false }
}

onMounted(() => window.addEventListener('focus', refresh))
onUnmounted(() => window.removeEventListener('focus', refresh))
watch(() => props.refreshVersion, refresh, { immediate: true })
watch(() => route.fullPath, (path) => { if (path === '/workspace') void refresh() })
</script>

<template>
  <section class="panel patient-home" aria-labelledby="patient-home-title">
    <div class="section-heading">
      <h2 id="patient-home-title">需要我处理的事</h2>
      <button class="secondary" type="button" @click="refresh">刷新事项</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-else-if="loading">正在读取事项…</p>
    <dl v-else class="patient-summary">
      <div :class="{ 'summary-urgent': urgentItems.length > 0 }">
        <dt>优先处理</dt><dd>{{ urgentItems.length }}</dd>
      </div>
      <div><dt>常规安排</dt><dd>{{ ordinaryItems.length }}</dd></div>
      <div><dt>新进展</dt><dd>{{ highlights.length }}</dd></div>
    </dl>
    <div v-if="!error && !loading && !items.length && !highlights.length" class="patient-empty">
      <p>目前没有待处理事项。</p>
      <RouterLink class="action-link" :to="{ query: route.query, hash: '#schedule-workspace' }">查看可预约时段</RouterLink>
    </div>
    <ul v-if="urgentItems.length" class="record-list" aria-label="优先处理事项">
      <li v-for="item in urgentItems" :key="item.type + item.id" class="record urgent-record">
        <div><strong>{{ item.title }}</strong><div>{{ item.timeLabel }} · {{ shanghaiTime(item.at) }}</div></div>
        <RouterLink class="action-link" :to="{ path: '/workspace', query: { [item.type]: item.id } }">{{ item.label }}</RouterLink>
      </li>
    </ul>
    <ul v-if="replies.length" class="record-list" aria-label="未读复诊协助回复">
      <li v-for="reply in replies" :key="reply.id" class="record unread-revisit" :data-reply-task="reply.objectId">
        <div><strong>复诊协助有新回复</strong><div>{{ followups.find(task => task.id === reply.objectId)?.title ?? shanghaiTime(reply.createdAt) }}</div></div>
        <button type="button" :disabled="openingMessage" @click="openMessage(reply)">查看回复与号源</button>
      </li>
    </ul>
    <ul v-if="progress.length" class="record-list" aria-label="未读进展">
      <li v-for="message in showAllProgress ? progress : progress.slice(0, 2)" :key="message.id" class="record" :data-progress-task="message.kind.startsWith('FOLLOWUP_') ? message.objectId : undefined"
        :data-callback-appointment="message.kind === 'APPOINTMENT_CALLBACK_UPDATED' ? message.objectId : undefined">
        <div><strong>{{ message.title }}</strong><div>{{ shanghaiTime(message.createdAt) }}</div></div>
        <button type="button" :disabled="openingMessage" @click="openMessage(message)">
          {{ message.kind === 'APPOINTMENT_CALLBACK_UPDATED' ? '查看回电进展' : '查看随访进展' }}
        </button>
      </li>
    </ul>
    <button v-if="progress.length > 2" class="secondary" type="button" @click="showAllProgress = !showAllProgress">
      {{ showAllProgress ? '收起进展' : '显示更多进展' }}
    </button>
    <ul v-if="ordinaryItems.length" class="record-list">
      <li v-for="item in expanded ? ordinaryItems : ordinaryItems.slice(0, 6)" :key="item.type + item.id" class="record">
        <div><strong>{{ item.title }}</strong><div>{{ item.timeLabel }} · {{ shanghaiTime(item.at) }}</div></div>
        <RouterLink class="action-link" :to="{ path: '/workspace', query: { [item.type]: item.id } }">{{ item.label }}</RouterLink>
      </li>
    </ul>
    <button v-if="ordinaryItems.length > 6" class="secondary" type="button" @click="expanded = !expanded">{{ expanded ? '收起事项' : '显示更多事项' }}</button>
  </section>
</template>
