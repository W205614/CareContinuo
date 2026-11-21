<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, mutate, type Actor } from './api'
import { shanghaiTime } from './time'

interface Message { id: number; kind: string; title: string; createdAt: string; readAt: string | null }
interface Work { id: number; objectId: number; kind: string; status: string; dueAt: string; resolution: string | null }
const props = defineProps<{ actor: Actor }>()
const patient = props.actor.roles.includes('PATIENT')
const messages = ref<Message[]>([])
const work = ref<Work[]>([])
const selected = ref<Message>()
const error = ref('')
const kinds: Record<string, string> = { STOPPED_APPOINTMENT: '停诊预约', NOTIFICATION_FAILED: '通知发送失败' }

async function refresh() {
  try {
    if (patient) messages.value = await api<Message[]>('/inbox')
    else work.value = await api<Work[]>('/work-items')
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

onMounted(refresh)
</script>

<template>
  <section class="panel" aria-labelledby="inbox-title">
    <div class="section-heading"><h2 id="inbox-title">{{ patient ? '站内消息' : '工作待办' }}</h2><button type="button" class="secondary" @click="refresh">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="patient">
      <p v-if="!messages.length">暂无消息。</p>
      <ul class="record-list">
        <li v-for="item in messages" :key="item.id" class="record">
          <div><strong>{{ item.title }}</strong><div>{{ shanghaiTime(item.createdAt) }} · {{ item.readAt ? '已读' : '未读' }}</div></div>
          <button class="secondary" type="button" @click="open(item.id)">查看消息</button>
          <div v-if="selected?.id === item.id" class="message-detail">{{ selected.title }}。请在预约列表查看最新状态。</div>
        </li>
      </ul>
    </template>
    <template v-else>
      <p v-if="!work.length">暂无待办。</p>
      <ul class="record-list">
        <li v-for="item in work" :key="item.id" class="record">
          <div><strong>{{ kinds[item.kind] ?? item.kind }} · {{ item.status === 'OPEN' ? '待处理' : '已处理' }}</strong><div>记录 {{ item.objectId }} · {{ shanghaiTime(item.dueAt) }}</div><div v-if="item.resolution">{{ item.resolution }}</div></div>
          <button v-if="item.status === 'OPEN' && item.kind === 'NOTIFICATION_FAILED'" class="secondary" type="button" @click="retry(item.id)">补发消息</button>
        </li>
      </ul>
    </template>
  </section>
</template>
