<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, mutate, type Actor } from './api'
import { shanghaiTime } from './time'

interface Message { id: number; kind: string; title: string; createdAt: string; readAt: string | null; correctedAt: string | null; replacementId: number | null }
interface Work { id: number; objectId: number; kind: string; status: string; dueAt: string; resolution: string | null }
const props = defineProps<{ actor: Actor }>()
const patient = props.actor.roles.includes('PATIENT')
const messages = ref<Message[]>([])
const work = ref<Work[]>([])
const selected = ref<Message>()
const departments = ref<{ id: number; name: string }[]>([])
const department = ref(0)
const state = ref('')
const cursor = ref<number | null>(null)
const error = ref('')
const kinds: Record<string, string> = { STOPPED_APPOINTMENT: '停诊预约', NOTIFICATION_FAILED: '通知发送失败', SOURCE_CHANGED: '更新随访草稿', FOLLOWUP_REVIEW: '审核随访', FOLLOWUP_OVERDUE: '随访逾期', PATIENT_FEEDBACK: '患者反馈', FOLLOWUP_TRANSFER: '接手随访', AGENT_CONFLICT: '核实 AI 发现的资料冲突' }

async function refresh() {
  try {
    if (patient) messages.value = await api<Message[]>('/inbox')
    else if (props.actor.demo) work.value = await api<Work[]>('/work-items')
    else await loadWork()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

async function open(id: number) {
  error.value = ''
  try {
    selected.value = await api<Message>(`/inbox/${id}`)
    await mutate(`/inbox/${id}/read`)
    await refresh()
  } catch (failure) { error.value = (failure as Error).message }
}

async function retry(id: number) {
  error.value = ''
  try { await mutate(`/work-items/${id}/retry`); await refresh() }
  catch (failure) { error.value = (failure as Error).message }
}

async function loadWork(afterId?: number) {
  error.value = ''
  try {
    const query = new URLSearchParams()
    if (department.value) query.set('departmentId', String(department.value))
    if (state.value) query.set('state', state.value)
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
  if (!patient && !props.actor.demo && props.actor.departmentIds.length) {
    try {
      const result = await api<{ departments: { id: number; name: string }[] }>('/schedule')
      departments.value = result.departments.filter((row) => props.actor.departmentIds.includes(row.id))
    } catch (failure) { error.value = (failure as Error).message }
  }
})
</script>

<template>
  <section class="panel" aria-labelledby="inbox-title">
    <div class="section-heading"><h2 id="inbox-title">{{ patient ? '站内消息' : '工作待办' }}</h2><button type="button" class="secondary" @click="refresh">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="patient">
      <p v-if="!messages.length">暂无消息。</p>
      <ul class="record-list">
        <li v-for="item in messages" :key="item.id" class="record">
          <div><strong>{{ item.title }}</strong><div>{{ shanghaiTime(item.createdAt) }} · {{ item.readAt ? '已读' : '未读' }}<span v-if="item.correctedAt"> · 已更正</span></div></div>
          <button class="secondary" type="button" @click="open(item.id)">查看消息</button>
          <div v-if="selected?.id === item.id" class="message-detail">
            <p>{{ selected.title }}。请在{{ selected.kind.startsWith('FOLLOWUP_') ? '我的随访' : '预约列表' }}查看最新状态。</p>
            <button v-if="selected.replacementId" type="button" class="secondary" @click="open(selected.replacementId)">查看更正后的消息</button>
          </div>
        </li>
      </ul>
    </template>
    <template v-else>
      <form v-if="!actor.demo && departments.length" class="field-grid" @submit.prevent="loadWork()">
        <label>待办科室<select v-model.number="department">
          <option :value="0">全部科室</option>
          <option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select></label>
        <label>状态<select v-model="state"><option value="">全部状态</option><option value="OPEN">待处理</option><option value="DONE">已处理</option></select></label>
        <button type="submit">筛选待办</button>
      </form>
      <p v-if="!work.length">暂无待办。</p>
      <ul class="record-list">
        <li v-for="item in work" :key="item.id" class="record">
          <div><strong>{{ kinds[item.kind] ?? item.kind }} · {{ item.status === 'OPEN' ? '待处理' : '已处理' }}</strong><div>记录 {{ item.objectId }} · {{ shanghaiTime(item.dueAt) }}</div><div v-if="item.resolution">{{ item.resolution }}</div></div>
          <button v-if="item.status === 'OPEN' && item.kind === 'NOTIFICATION_FAILED'" class="secondary" type="button" @click="retry(item.id)">补发消息</button>
        </li>
      </ul>
      <button v-if="cursor" class="secondary" type="button" @click="loadWork(cursor!)">载入更多待办</button>
    </template>
  </section>
</template>
