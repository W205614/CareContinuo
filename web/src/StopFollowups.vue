<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { api, mutate } from './api'
import { appointmentStates } from './appointments'
import { fromShanghaiInput, shanghaiTime, toShanghaiInput } from './time'

interface StoppedAppointment {
  id: number; status: string; startsAt: string; department: string; patientName: string; stopReason: string
  messageAt: string | null; readAt: string | null; contactedAt: string | null; contactOutcome: string | null
  callbackStatus: string | null; callbackDueAt: string | null; callbackOwnerId: number | null; callbackOwner: string | null
  callbackResolution: string | null
}
interface Contact {
  id: number; outcome: string; note: string | null; contactedAt: string; operator: string
  callbackOwner: string | null; callbackDueAt: string | null
}
interface Assignee { id: number; name: string }
interface StopCursor { startsAt: string; id: number }
interface StopPage { rows: StoppedAppointment[]; nextCursor: StopCursor | null }

const props = defineProps<{ appointmentId?: number; refreshVersion: number; actorId: number }>()
const emit = defineEmits<{ changed: [] }>()
const outcomes: Record<string, string> = { REACHED: '已联系到患者', NO_ANSWER: '未接通', CALL_BACK: '约定回电' }
const processed = ref(false)
const rows = ref<StoppedAppointment[]>([])
const nextCursor = ref<StopCursor | null>(null)
const pages = ref<(StopCursor | null)[]>([null])
const selectedId = ref<number>()
const contacts = ref<Contact[]>([])
const outcome = ref('REACHED')
const assignees = ref<Assignee[]>([])
const callbackOwner = ref<number>()
const callbackAt = ref('')
const selected = computed(() => rows.value.find(item => item.id === selectedId.value))
const canRecordOutcome = computed(() => selected.value?.callbackStatus !== 'OPEN' || selected.value.callbackOwnerId === props.actorId)
const note = ref('')
const busy = ref(false)
const error = ref('')
let refreshVersion = 0

async function refresh() {
  const version = ++refreshVersion
  const query = new URLSearchParams({ processed: String(processed.value) })
  const id = props.appointmentId
  if (id) {
    query.set('appointmentId', String(id))
    const detail = await api<StoppedAppointment[]>('/appointments/stopped?' + query)
    if (version === refreshVersion) rows.value = detail
    return
  }
  const cursor = pages.value.at(-1)
  if (cursor) {
    query.set('afterStart', cursor.startsAt)
    query.set('afterId', String(cursor.id))
  }
  const page = await api<StopPage>('/appointments/stopped/page?' + query)
  if (version !== refreshVersion) return
  rows.value = page.rows
  nextCursor.value = page.nextCursor
}

async function openContacts(id: number) {
  error.value = ''
  selectedId.value = id
  contacts.value = []
  assignees.value = []
  note.value = ''
  outcome.value = canRecordOutcome.value ? 'REACHED' : 'CALL_BACK'
  callbackOwner.value = selected.value?.callbackOwnerId ?? props.actorId
  callbackAt.value = selected.value?.callbackDueAt ? toShanghaiInput(selected.value.callbackDueAt) : ''
  try {
    const [history, people] = await Promise.all([
      api<Contact[]>(`/appointments/${id}/stop-contacts`),
      selected.value?.status === 'STOPPED' ? api<Assignee[]>(`/appointments/${id}/stop-callback-assignees`) : Promise.resolve([]),
    ])
    if (selectedId.value !== id) return
    contacts.value = history
    assignees.value = people
    if (!people.some(person => person.id === callbackOwner.value)) callbackOwner.value = props.actorId
  } catch (failure) { error.value = (failure as Error).message }
}

async function record(id: number) {
  busy.value = true
  error.value = ''
  try {
    await mutate(`/appointments/${id}/stop-contacts`, {
      outcome: outcome.value, note: note.value,
      ...(outcome.value === 'CALL_BACK' ? { callbackOwnerId: callbackOwner.value, callbackDueAt: fromShanghaiInput(callbackAt.value) } : {}),
    })
    emit('changed')
    await refresh()
    await openContacts(id)
  } catch (failure) { error.value = (failure as Error).message }
  finally { busy.value = false }
}

async function changePage(forward: boolean) {
  if (forward && nextCursor.value) pages.value.push(nextCursor.value)
  else if (!forward && pages.value.length > 1) pages.value.pop()
  selectedId.value = undefined
  busy.value = true
  try { await load() }
  finally { busy.value = false }
}

async function load() {
  error.value = ''
  try {
    await refresh()
    if (selected.value) await openContacts(selected.value.id)
  } catch (failure) { error.value = (failure as Error).message }
}

watch([() => props.appointmentId, processed], async ([id]) => {
  pages.value = [null]
  nextCursor.value = null
  selectedId.value = undefined
  await load()
  if (!id || props.appointmentId !== id) return
  if (rows.value.some((row) => row.id === id)) await openContacts(id)
  else error.value = '无法查看此停诊预约。'
  await nextTick()
  const panel = document.getElementById('stop-followups')
  panel?.focus({ preventScroll: true })
  panel?.scrollIntoView({ block: 'start' })
}, { immediate: true })
watch(() => props.refreshVersion, load)
</script>

<template>
  <section id="stop-followups" tabindex="-1" class="panel" aria-labelledby="appointment-title">
    <div class="section-heading">
      <h2 id="appointment-title">停诊影响记录</h2>
      <button class="secondary" type="button" @click="load">刷新停诊清单</button>
    </div>
    <RouterLink v-if="appointmentId" :to="{ path: '/workspace' }">查看停诊清单</RouterLink>
    <label v-else>处理状态<select v-model="processed">
      <option :value="false">待处理</option><option :value="true">已处理</option>
    </select></label>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="!rows.length && !error">暂无停诊记录。</p>
    <ul class="record-list">
      <li v-for="item in rows" :key="item.id" class="record" :data-appointment-id="item.id">
        <div>
          <strong>{{ item.patientName }} · {{ appointmentStates[item.status] }}</strong>
          <div>{{ item.department }} · {{ shanghaiTime(item.startsAt) }}</div>
          <div>{{ item.stopReason }}</div>
          <div>{{ item.messageAt ? '站内消息已生成' : '站内消息未生成' }} · {{ item.readAt ? '患者已读' : '患者未读' }}</div>
          <div>{{ item.contactOutcome && item.contactedAt ? outcomes[item.contactOutcome] + ' · ' + shanghaiTime(item.contactedAt) : '未记录人工联系' }}</div>
          <div v-if="item.callbackStatus === 'OPEN' && item.callbackDueAt">
            待回电 · {{ item.callbackOwner }} · {{ shanghaiTime(item.callbackDueAt) }}（北京时间）
          </div>
          <div v-else-if="item.callbackStatus === 'DONE'">回电已关闭 · {{ item.callbackResolution }}</div>
        </div>
        <button v-if="selectedId !== item.id" class="secondary" type="button" @click="openContacts(item.id)">查看联系记录</button>
        <div v-else class="message-detail">
          <p v-if="!contacts.length">暂无联系记录。</p>
          <ul v-else class="source-list">
            <li v-for="contact in contacts" :key="contact.id">
              {{ shanghaiTime(contact.contactedAt) }} · {{ contact.operator }} · {{ outcomes[contact.outcome] }}
              <span v-if="contact.callbackDueAt"> · {{ contact.callbackOwner }} · 回电时间 {{ shanghaiTime(contact.callbackDueAt) }}</span>
              <span v-if="contact.note"> · {{ contact.note }}</span>
            </li>
          </ul>
          <form v-if="item.status === 'STOPPED'" class="field-grid" @submit.prevent="record(item.id)">
            <label>联系结果<select v-model="outcome">
              <option v-if="canRecordOutcome" value="REACHED">{{ outcomes.REACHED }}</option>
              <option v-if="canRecordOutcome" value="NO_ANSWER">{{ outcomes.NO_ANSWER }}</option>
              <option value="CALL_BACK">{{ outcomes.CALL_BACK }}</option>
            </select></label>
            <template v-if="outcome === 'CALL_BACK'">
              <label>回电负责人<select v-model.number="callbackOwner" required>
                <option v-for="person in assignees" :key="person.id" :value="person.id">{{ person.name }}</option>
              </select></label>
              <label>回电时间（北京时间）<input v-model="callbackAt" type="datetime-local" required /></label>
            </template>
            <label>联系备注<textarea maxlength="1000" v-model.trim="note" /></label>
            <button :disabled="busy">保存联系记录</button>
          </form>
        </div>
      </li>
    </ul>
    <div v-if="!appointmentId && (nextCursor || pages.length > 1)" class="row-actions">
      <button type="button" class="secondary" :disabled="busy || pages.length === 1" @click="changePage(false)">上一页</button>
      <button type="button" class="secondary" :disabled="busy || !nextCursor" @click="changePage(true)">下一页</button>
    </div>
  </section>
</template>
