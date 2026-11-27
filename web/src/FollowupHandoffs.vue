<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { api, mutate } from './api'
import { shanghaiTime } from './time'

interface Handoff {
  id: number; senderId: number; recipientId: number; sender: string; recipient: string
  fromDepartment: string; toDepartment: string; taskId: number | null
  status: 'PENDING' | 'ACCEPTED' | 'RETURNED' | 'TIMED_OUT' | 'SUPERSEDED'
  dueAt: string; resolvedAt: string | null; returnReason: string | null
}

const props = defineProps<{ actorId: number; handoffId?: number; refreshVersion: number }>()
const emit = defineEmits<{ changed: [] }>()
const states: Record<Handoff['status'], string> = {
  PENDING: '待接手', ACCEPTED: '已接手', RETURNED: '已退回', TIMED_OUT: '已超时', SUPERSEDED: '随访已变更',
}
const handoffs = ref<Handoff[]>([])
const selected = ref<Handoff>()
const detailPanel = ref<HTMLElement>()
const returning = ref(false)
const reason = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
let loadNumber = 0
const canDecide = computed(() => selected.value?.status === 'PENDING' && selected.value.recipientId === props.actorId)

function select(item?: Handoff) {
  selected.value = item
  returning.value = false
  reason.value = ''
  error.value = ''
  notice.value = ''
}

async function refresh(id = selected.value?.id) {
  const load = ++loadNumber
  try {
    const rows = await api<Handoff[]>('/followups/handoffs')
    const item = id ? rows.find(row => row.id === id) ?? await api<Handoff>(`/followups/handoffs/${id}`) : undefined
    if (load !== loadNumber) return
    handoffs.value = rows
    selected.value = item
  } catch (failure) {
    if (load !== loadNumber) return
    selected.value = undefined
    error.value = (failure as Error).message
  }
}

watch([() => props.handoffId, () => props.refreshVersion], async ([id], previous) => {
  const openFromLink = id !== undefined && id !== previous?.[0]
  if (openFromLink) select()
  await refresh(id ?? selected.value?.id)
  if (openFromLink && selected.value?.id === id) {
    await nextTick()
    detailPanel.value?.focus({ preventScroll: true })
    detailPanel.value?.scrollIntoView({ block: 'start' })
  }
}, { immediate: true })

async function decide(action: 'ACCEPT' | 'RETURN') {
  if (!selected.value) return
  const id = selected.value.id
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await mutate(`/followups/handoffs/${id}/decision`, { action, reason: action === 'RETURN' ? reason.value : null })
    returning.value = false
    reason.value = ''
    notice.value = action === 'ACCEPT' ? '已接手随访。' : '已退回，随访由原负责人继续跟进。'
    emit('changed')
  } catch (failure) {
    error.value = (failure as Error).message
    await refresh(id)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel" aria-label="跨科室随访交接">
    <div class="section-heading">
      <h3>跨科室随访交接</h3>
      <button type="button" class="secondary" @click="refresh()">刷新交接</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!handoffs.length">暂无交接记录。</p>
    <ul v-else class="record-list">
      <li v-for="item in handoffs" :key="item.id" class="record" :data-handoff-id="item.id">
        <div>
          <strong>{{ item.senderId === actorId ? '我发起的' : '发给我的' }} · {{ item.fromDepartment }} → {{ item.toDepartment }}</strong>
          <div>{{ states[item.status] }} · {{ item.sender }} → {{ item.recipient }}</div>
        </div>
        <button type="button" class="secondary" @click="select(item)">查看交接</button>
      </li>
    </ul>
    <article v-if="selected" ref="detailPanel" class="panel" tabindex="-1" aria-label="交接详情">
      <h4>交接 #{{ selected.id }} · {{ states[selected.status] }}</h4>
      <p>{{ selected.fromDepartment }} {{ selected.sender }} → {{ selected.toDepartment }} {{ selected.recipient }}</p>
      <p>接手期限 {{ shanghaiTime(selected.dueAt) }}<template v-if="selected.resolvedAt"> · 处理时间 {{ shanghaiTime(selected.resolvedAt) }}</template></p>
      <p v-if="selected.status === 'PENDING'">确认接手前，由原负责人继续跟进随访。</p>
      <p v-if="selected.status === 'TIMED_OUT' || selected.status === 'RETURNED'">交接未生效，请由当前负责人继续跟进。</p>
      <p v-if="selected.returnReason">退回原因 · {{ selected.returnReason }}</p>
      <RouterLink v-if="selected.taskId" :to="{ path: '/workspace', query: { followup: selected.taskId } }">打开随访</RouterLink>
      <div v-if="canDecide && !returning" class="row-actions">
        <button type="button" :disabled="busy" @click="decide('ACCEPT')">确认接手</button>
        <button type="button" class="secondary" :disabled="busy" @click="returning = true">退回交接</button>
      </div>
      <form v-if="canDecide && returning" class="field-grid" @submit.prevent="decide('RETURN')">
        <label>退回原因<textarea v-model.trim="reason" maxlength="200" required /></label>
        <button :disabled="busy">确认退回</button>
        <button type="button" class="secondary" :disabled="busy" @click="returning = false">取消退回</button>
      </form>
    </article>
  </section>
</template>
