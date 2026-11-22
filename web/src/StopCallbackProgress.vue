<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from './api'
import { shanghaiTime } from './time'

const props = defineProps<{ appointmentId: number }>()
const labels = {
  NOT_SCHEDULED: '暂未安排回电',
  SCHEDULED: '已安排回电',
  NO_ANSWER: '未接通，待后续联系',
  CONTACTED: '已联系',
  RESCHEDULED: '已改约，无需回电',
  CANCELLED: '已取消预约，无需回电',
}
interface Progress {
  status: keyof typeof labels | 'NOT_APPLICABLE'
  expectedAt: string | null
}
const progress = ref<Progress>()
const unavailable = ref(false)
let timer: number | undefined
let refreshing = false

async function refresh() {
  if (refreshing) return
  refreshing = true
  try {
    progress.value = await api<Progress>(`/appointments/${props.appointmentId}/callback-status`)
    unavailable.value = false
  } catch {
    unavailable.value = true
  } finally { refreshing = false }
}

onMounted(() => {
  void refresh()
  timer = window.setInterval(refresh, 5000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <section v-if="progress?.status !== 'NOT_APPLICABLE'" aria-label="停诊回电" aria-live="polite">
    <strong>停诊回电</strong>
    <div v-if="unavailable">回电状态暂时无法更新，请稍后查看。</div>
    <template v-else-if="progress">
      <div>{{ labels[progress.status] }}</div>
      <div v-if="progress.expectedAt">
        <div>预计回电时间（北京时间）</div>
        <div>{{ shanghaiTime(progress.expectedAt) }}</div>
      </div>
      <div v-if="progress.status === 'NO_ANSWER'">
        <p>新的回电时间待确认，约定后会收到站内消息。</p>
        <p>如需尽快处理，可在工作日 09:00–17:00（北京时间）联系原科室，也可在此改约或取消。</p>
      </div>
    </template>
    <div v-else>正在查询回电状态…</div>
  </section>
</template>
