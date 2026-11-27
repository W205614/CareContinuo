<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { requestCategories } from './followup'
import { api, mutate, type Actor } from './api'
import { shanghaiTime } from './time'

import { messageTarget, type InboxMessage as Message } from './notifications'
type DeliveryStatus = 'FAILED' | 'PENDING' | 'SENT' | 'RETRY_FAILED' | 'STALE'
interface Work {
  id: number; objectId: number; kind: string; status: string; dueAt: string; resolution: string | null
  owner: string | null; targetType: string | null; targetId: number | null; requestCategory: string | null
  deliveryStatus: DeliveryStatus | null
}
const props = defineProps<{ actor: Actor; refreshVersion: number }>()
const emit = defineEmits<{ readMessage: [] }>()
const router = useRouter()
const patient = props.actor.roles.includes('PATIENT')
const messages = ref<Message[]>([])
const messageRead = ref('')
const messageGroup = ref('')
const messageCursor = ref<number | null>(null)
const work = ref<Work[]>([])
const selected = ref<Message>()
const departments = ref<{ id: number; name: string }[]>([])
const department = ref(0)
const state = ref('OPEN')
const kind = ref('')
const cursor = ref<number | null>(null)
const error = ref('')
const submittingRetries = ref(new Set<number>())
const recentRetry = ref<{ id: number; status: DeliveryStatus }>()
const deliveryLabels: Record<DeliveryStatus, string> = {
  FAILED: '待补发', PENDING: '已提交补发，等待投递', SENT: '消息已生成',
  RETRY_FAILED: '补发失败', STALE: '消息已失效',
}
let deliveryTimer: number | undefined
let checkingDelivery = false
const kinds: Record<string, string> = { STOPPED_APPOINTMENT: '停诊预约', STOP_CALLBACK: '停诊回电', NOTIFICATION_FAILED: '通知发送失败', SOURCE_CHANGED: '更新随访草稿', FOLLOWUP_REVIEW: '审核随访', FOLLOWUP_OVERDUE: '随访逾期', PATIENT_FEEDBACK: '患者反馈', FOLLOWUP_TRANSFER: '接手随访', AGENT_CONFLICT: '核实 AI 发现的资料冲突', FOLLOWUP_REQUEST: '患者协助或反馈', FOLLOWUP_HANDOFF: '跨科室随访交接' }

async function refresh() {
  try {
    if (patient) await loadMessages()
    else await loadWork()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

async function loadMessages(afterId?: number) {
  const query = new URLSearchParams()
  if (messageRead.value) query.set('read', messageRead.value)
  if (messageGroup.value) query.set('group', messageGroup.value)
  if (afterId) query.set('afterId', String(afterId))
  try {
    const page = await api<{ messages: Message[]; nextCursor: number | null }>('/inbox/page?' + query)
    messages.value = afterId ? [...messages.value, ...page.messages] : page.messages
    messageCursor.value = page.nextCursor
  } catch (failure) { error.value = (failure as Error).message }
}

async function open(id: number) {
  error.value = ''
  try {
    selected.value = await api<Message>(`/inbox/${id}`)
    await mutate(`/inbox/${id}/read`)
    emit('readMessage')
  } catch (failure) { error.value = (failure as Error).message }
}

async function openTarget(item: Message) {
  error.value = ''
  try {
    await mutate(`/inbox/${item.id}/read`)
    await router.push(messageTarget(item))
    emit('readMessage')
  } catch (failure) { error.value = (failure as Error).message }
}

function workLink(item: Work) {
  return { path: '/workspace', query: {
    [item.targetType!]: item.targetId,
    request: item.kind === 'FOLLOWUP_REQUEST' ? item.objectId : undefined,
  } }
}

function deadline(item: Work) {
  const remaining = Date.parse(item.dueAt) - Date.now()
  if (item.status !== 'OPEN' || (item.kind === 'STOPPED_APPOINTMENT' || item.kind === 'NOTIFICATION_FAILED')) return ''
  return remaining <= 0 ? '已逾期' : remaining <= 86_400_000 ? '临近到期' : ''
}

async function retry(id: number) {
  error.value = ''
  submittingRetries.value.add(id)
  try {
    await mutate(`/work-items/${id}/retry`)
    recentRetry.value = { id, status: 'PENDING' }
    const item = work.value.find(row => row.id === id)
    if (item) item.deliveryStatus = 'PENDING'
  } catch (failure) { error.value = (failure as Error).message }
  finally { submittingRetries.value.delete(id) }
}

async function refreshDeliveries() {
  if (checkingDelivery) return
  const pending = new Set(work.value.filter(row => row.deliveryStatus === 'PENDING').map(row => row.id))
  if (recentRetry.value?.status === 'PENDING') pending.add(recentRetry.value.id)
  if (!pending.size) return
  checkingDelivery = true
  try {
    const updates = await Promise.all([...pending].map(id => api<Work>(`/work-items/${id}`)))
    const byId = new Map(updates.map(item => [item.id, item]))
    const latest = recentRetry.value && byId.get(recentRetry.value.id)
    if (latest?.deliveryStatus) recentRetry.value = { id: latest.id, status: latest.deliveryStatus }
    work.value = work.value.map(row => byId.get(row.id) ?? row)
      .filter(row => !state.value || row.status === state.value)
  } catch (failure) {
    await loadWork()
    if (recentRetry.value && !work.value.some(row => row.id === recentRetry.value?.id)) recentRetry.value = undefined
    error.value = (failure as Error).message
  } finally {
    checkingDelivery = false
  }
}

async function loadWork(afterId?: number) {
  error.value = ''
  try {
    const query = new URLSearchParams()
    if (department.value) query.set('departmentId', String(department.value))
    if (state.value) query.set('state', state.value)
    if (kind.value) query.set('kind', kind.value)
    if (afterId) query.set('afterId', String(afterId))
    const payload = await api<{ work: Work[]; nextCursor: number | null }>('/work-items/page?' + query)
    work.value = afterId ? [...work.value, ...payload.work] : payload.work
    cursor.value = payload.nextCursor
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(async () => {
  await refresh()
  if (!patient) {
    try {
      const result = await api<{ departments: { id: number; name: string }[] }>('/schedule')
      departments.value = result.departments.filter((row) => props.actor.demo || props.actor.departmentIds.includes(row.id))
    } catch (failure) { error.value = (failure as Error).message }
  }
})
onMounted(() => {
  if (!patient) deliveryTimer = window.setInterval(refreshDeliveries, 2000)
})
onUnmounted(() => window.clearInterval(deliveryTimer))
watch(() => props.refreshVersion, refresh)
</script>

<template>
  <section class="panel" aria-labelledby="inbox-title">
    <div class="section-heading"><h2 id="inbox-title">{{ patient ? '站内消息' : '工作待办' }}</h2><button type="button" class="secondary" @click="refresh">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="patient">
      <form class="field-grid inbox-filters" @submit.prevent="loadMessages()">
        <label>消息阅读状态<select v-model="messageRead">
          <option value="">全部消息</option><option value="false">未读</option><option value="true">已读</option>
        </select></label>
        <label>消息类型<select v-model="messageGroup">
          <option value="">全部类型</option><option value="APPOINTMENT">预约</option><option value="FOLLOWUP">随访</option>
        </select></label>
        <button type="submit">筛选消息</button>
      </form>
      <p v-if="!messages.length">暂无消息。</p>
      <ul class="record-list">
        <li v-for="item in messages" :key="item.id" class="record">
          <div><strong>{{ item.title }}</strong><div>{{ shanghaiTime(item.createdAt) }} · {{ item.readAt ? '已读' : '未读' }}<span v-if="item.correctedAt"> · 已更正</span></div></div>
          <div class="row-actions">
            <button class="secondary" type="button" @click="open(item.id)">查看消息</button>
            <button type="button" @click="openTarget(item)">{{ item.revisitRequestId && !item.correctedAt ? '查看复诊协助回复' : item.kind.startsWith('FOLLOWUP_') ? '查看随访安排' : item.kind === 'APPOINTMENT_STOPPED' ? '处理停诊' : item.kind === 'APPOINTMENT_CALLBACK_UPDATED' ? '查看回电进展' : '查看预约' }}</button>
          </div>
          <div v-if="selected?.id === item.id" class="message-detail">
            <p>{{ selected.title }}。</p>
            <button v-if="selected.replacementId" type="button" class="secondary" @click="open(selected.replacementId)">查看更正后的消息</button>
          </div>
        </li>
      </ul>
      <button v-if="messageCursor" class="secondary" type="button" @click="loadMessages(messageCursor)">载入更多消息</button>
    </template>
    <template v-else>
      <form class="field-grid inbox-filters" @submit.prevent="loadWork()">
        <label>待办科室<select v-model.number="department">
          <option :value="0">全部科室</option>
          <option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select></label>
        <label>状态<select v-model="state"><option value="">全部状态</option><option value="OPEN">待处理</option><option value="DONE">已处理</option></select></label>
        <label>类型<select v-model="kind"><option value="">全部类型</option><option v-for="(label, key) in kinds" :key="key" :value="key">{{ label }}</option></select></label>
        <button type="submit">筛选待办</button>
      </form>
      <p v-if="recentRetry && !work.some(row => row.id === recentRetry?.id)" role="status">{{ deliveryLabels[recentRetry.status] }}</p>
      <p v-if="!work.length">暂无待办。</p>
      <ul class="record-list">
        <li v-for="item in work" :key="item.id" class="record">
          <div>
            <strong>{{ item.requestCategory ? requestCategories[item.requestCategory] : kinds[item.kind] }} · {{ item.status === 'OPEN' ? '待处理' : '已处理' }}</strong>
            <div>{{ item.owner ?? '运营共享' }} · {{ shanghaiTime(item.dueAt) }} <strong v-if="deadline(item)" class="error">{{ deadline(item) }}</strong></div>
            <div v-if="item.status === 'OPEN' && (item.kind === 'PATIENT_FEEDBACK' || item.requestCategory === 'QUESTION')">患者待回复</div>
            <div v-if="item.deliveryStatus" role="status">{{ deliveryLabels[item.deliveryStatus] }}</div>
            <div v-if="item.resolution && (!item.deliveryStatus || item.resolution !== deliveryLabels[item.deliveryStatus])">{{ item.resolution }}</div>
          </div>
          <div class="row-actions">
            <RouterLink v-if="item.targetType && item.targetId" class="action-link" :to="workLink(item)">打开处理</RouterLink>
            <button v-if="item.status === 'OPEN' && (item.deliveryStatus === 'FAILED' || item.deliveryStatus === 'RETRY_FAILED')"
              :disabled="submittingRetries.has(item.id)" class="secondary" type="button" @click="retry(item.id)">补发消息</button>
          </div>
        </li>
      </ul>
      <button v-if="cursor" class="secondary" type="button" @click="loadWork(cursor!)">载入更多待办</button>
    </template>
  </section>
</template>
