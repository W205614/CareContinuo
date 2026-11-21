<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, mutate, type Actor } from './api'
import { fromShanghaiInput, shanghaiTime } from './time'

interface Slot { id: number; doctorName: string; department: string; startsAt: string; endsAt: string; remaining: number; stoppedAt: string | null; stopReason: string | null }
interface Schedule { departments: { id: number; name: string }[]; doctors: { id: number; displayName: string }[]; slots: Slot[] }
interface Appointment { id: number; slotId: number; doctor: string; department: string; startsAt: string; endsAt: string; status: string; patientName: string; stopReason: string | null }

const props = defineProps<{ actor: Actor }>()
const patient = props.actor.roles.includes('PATIENT')
const doctor = props.actor.roles.includes('DOCTOR')
const operations = props.actor.roles.includes('OPERATIONS')
const schedule = ref<Schedule>({ departments: [], doctors: [], slots: [] })
const records = ref<Appointment[]>([])
const error = ref('')
const status = ref('')
const busy = ref(false)
const activeMove = ref<number>()
const activeCompletion = ref<number>()
const activeCorrection = ref<number>()
const activeStop = ref<number>()
const reason = ref('')
const note = ref('')
const returnPlan = ref('')
const newSlot = ref({ doctorId: 0, startsAt: '', endsAt: '', capacity: 1 })
const newDepartment = ref('')
const newDoctor = ref({ username: '', displayName: '', departmentId: 0 })
const usableSlots = computed(() => schedule.value.slots.filter((slot) => slot.remaining > 0 && !slot.stoppedAt))

const states: Record<string, string> = {
  BOOKED: '已预约', CHECKED_IN: '已签到', IN_CONSULT: '已接诊', COMPLETED: '已完成',
  CANCELLED: '已取消', RESCHEDULED: '已改约', NO_SHOW: '未到诊', STOPPED: '停诊待处理',
}

async function refresh() {
  const [newSchedule, appointments] = await Promise.all([api<Schedule>('/schedule'), api<Appointment[]>('/appointments')])
  schedule.value = newSchedule
  records.value = appointments
  newSlot.value.doctorId ||= newSchedule.doctors[0]?.id ?? 0
  newDoctor.value.departmentId ||= newSchedule.departments[0]?.id ?? 0
}

async function run(action: () => Promise<unknown>, done: string) {
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    await action()
    status.value = done
    await refresh()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

async function submitAppointment(slotId: number) {
  const previous = activeMove.value
  const path = previous ? `/appointments/${previous}/reschedule` : '/appointments'
  await run(async () => {
    await mutate(path, { slotId }, { 'Idempotency-Key': crypto.randomUUID() })
    activeMove.value = undefined
  }, previous ? '改约已确认。' : '预约已确认。')
}

function cancelAppointment(id: number) {
  return run(() => mutate(`/appointments/${id}/cancel`, undefined, { 'Idempotency-Key': crypto.randomUUID() }), '预约已取消。')
}

function submitSlot() {
  const draft = newSlot.value
  return run(() => mutate('/schedule-slots', {
    doctorId: draft.doctorId, startsAt: fromShanghaiInput(draft.startsAt), endsAt: fromShanghaiInput(draft.endsAt),
    capacity: draft.capacity,
  }), '号源已开放。')
}

function reportStop(id: number) { activeStop.value = id; reason.value = '' }
function reportCorrection(id: number) { activeCorrection.value = id; reason.value = '' }

onMounted(async () => {
  try { await refresh() } catch (failure) { error.value = (failure as Error).message }
})
</script>

<template>
  <div class="workspace-section">
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="status" role="status">{{ status }}</p>
    <section v-if="operations" class="panel" aria-labelledby="manage-title">
      <h2 id="manage-title">排班与放号</h2>
      <form class="field-grid" @submit.prevent="submitSlot">
        <label>医生<select v-model.number="newSlot.doctorId" required><option v-for="person in schedule.doctors" :key="person.id" :value="person.id">{{ person.displayName }}</option></select></label>
        <label>开始时间（北京时间）<input v-model="newSlot.startsAt" type="datetime-local" required /></label>
        <label>结束时间（北京时间）<input v-model="newSlot.endsAt" type="datetime-local" required /></label>
        <label>号源数量<input v-model.number="newSlot.capacity" min="1" max="1000" type="number" required /></label>
        <button :disabled="busy">开放号源</button>
      </form>
      <details>
        <summary>维护科室与医生</summary>
        <form class="field-grid" @submit.prevent="run(() => mutate('/departments', { name: newDepartment }), '科室已保存。')">
          <label>科室名称<input v-model.trim="newDepartment" maxlength="100" required /></label>
          <button :disabled="busy">保存科室</button>
        </form>
        <form class="field-grid" @submit.prevent="run(() => mutate('/doctors', newDoctor), '医生资料已保存。')">
          <label>医生账号<input v-model.trim="newDoctor.username" maxlength="80" required /></label>
          <label>医生姓名<input v-model.trim="newDoctor.displayName" maxlength="100" required /></label>
          <label>科室<select v-model.number="newDoctor.departmentId"><option v-for="item in schedule.departments" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <button :disabled="busy">保存医生</button>
        </form>
      </details>
      <ul class="record-list">
        <li v-for="slot in schedule.slots" :key="slot.id" class="record">
          <div><strong>{{ slot.doctorName }} · {{ slot.department }}</strong><div>{{ shanghaiTime(slot.startsAt) }}</div><div>{{ slot.stoppedAt ? `已停诊 · ${slot.stopReason}` : `剩余 ${slot.remaining} 个号源` }}</div></div>
          <button v-if="!slot.stoppedAt && activeStop !== slot.id" class="secondary" type="button" @click="reportStop(slot.id)">停诊</button>
          <form v-if="activeStop === slot.id" class="field-grid" @submit.prevent="run(async () => { await mutate(`/schedule-slots/${slot.id}/stop`, { reason }); activeStop = undefined }, '已停诊并通知受影响的预约。')">
            <label>停诊原因<input v-model.trim="reason" maxlength="400" required /></label><button :disabled="busy">确认停诊</button>
          </form>
        </li>
      </ul>
    </section>
    <section v-if="patient" class="panel" aria-labelledby="slots-title">
      <h2 id="slots-title">{{ activeMove ? '选择新的时段' : '可预约时段' }}</h2>
      <p v-if="activeMove"><button class="secondary" type="button" @click="activeMove = undefined">返回我的预约</button></p>
      <p v-if="!usableSlots.length">暂无可预约号源。</p>
      <ul class="record-list">
        <li v-for="slot in usableSlots" :key="slot.id" class="record">
          <div><strong>{{ slot.doctorName }} · {{ slot.department }}</strong><div>{{ shanghaiTime(slot.startsAt) }}</div><div>剩余 {{ slot.remaining }} 个号源</div></div>
          <button :disabled="busy" type="button" @click="submitAppointment(slot.id)">{{ activeMove ? '确认改约' : '预约此时段' }}</button>
        </li>
      </ul>
    </section>
    <section class="panel" aria-labelledby="appointment-title">
      <h2 id="appointment-title">{{ patient ? '我的预约' : doctor ? '接诊列表' : '停诊影响记录' }}</h2>
      <p v-if="!records.length">暂无记录。</p>
      <ul class="record-list">
        <li v-for="item in records" :key="item.id" class="record" :data-appointment-id="item.id">
          <div><strong>{{ patient ? item.doctor : item.patientName }} · {{ states[item.status] }}</strong><div>{{ shanghaiTime(item.startsAt) }}</div><div v-if="item.status === 'STOPPED'">{{ item.stopReason }}</div></div>
          <div class="row-actions">
            <template v-if="patient && ['BOOKED', 'STOPPED'].includes(item.status)">
              <button type="button" v-if="item.status === 'BOOKED'" :disabled="busy" class="secondary" @click="run(() => mutate(`/appointments/${item.id}/check-in`), '签到已确认。')">签到</button>
              <button type="button" class="secondary" @click="activeMove = item.id">改约</button>
              <button type="button" :disabled="busy" class="secondary" @click="cancelAppointment(item.id)">取消预约</button>
            </template>
            <button v-if="doctor && item.status === 'CHECKED_IN'" :disabled="busy" type="button" @click="run(() => mutate(`/appointments/${item.id}/start`), '已开始接诊。')">开始接诊</button>
            <button v-if="doctor && item.status === 'IN_CONSULT' && activeCompletion !== item.id" type="button" @click="activeCompletion = item.id; note = ''; returnPlan = ''">录入接诊结果</button>
            <button v-if="doctor && item.status === 'NO_SHOW' && activeCorrection !== item.id" type="button" class="secondary" @click="reportCorrection(item.id)">更正签到</button>
          </div>
          <form v-if="activeCompletion === item.id" class="field-grid" @submit.prevent="run(async () => { await mutate(`/appointments/${item.id}/complete`, { note, returnPlan }); activeCompletion = undefined }, '接诊已完成。')">
            <label>模拟接诊记录<textarea v-model.trim="note" maxlength="4000" required /></label>
            <label>复诊安排<textarea v-model.trim="returnPlan" maxlength="2000" /></label>
            <button :disabled="busy">确认完成</button>
          </form>
          <form v-if="activeCorrection === item.id" class="field-grid" @submit.prevent="run(async () => { await mutate(`/appointments/${item.id}/correct-attendance`, { reason }); activeCorrection = undefined }, '签到已更正。')">
            <label>更正原因<input v-model.trim="reason" maxlength="400" required /></label><button :disabled="busy">确认更正</button>
          </form>
        </li>
      </ul>
    </section>
  </div>
</template>
