<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import StopFollowups from './StopFollowups.vue'
import StopCallbackProgress from './StopCallbackProgress.vue'
import RevisitAssistanceForm from './RevisitAssistanceForm.vue'
import { api, mutate, type Actor } from './api'
import { appointmentStates as states, noRevisitSlots, revisitQuery, type Appointment, type RevisitFilters, type RevisitSlotPage, type Schedule, type Slot } from './appointments'
import RevisitSlotFilters from './RevisitSlotFilters.vue'
import { requestStates, type Choice, type Directory, type Followup } from './followup'
import { fromShanghaiInput, shanghaiTime, shanghaiTimeOnVisitDay, toShanghaiInput } from './time'

const props = defineProps<{ actor: Actor; refreshVersion: number; appointmentId?: number }>()
const router = useRouter()
const emit = defineEmits<{ changed: [] }>()
const patient = props.actor.roles.includes('PATIENT')
const doctor = props.actor.roles.includes('DOCTOR')
const operations = props.actor.roles.includes('OPERATIONS')
const schedule = ref<Schedule>({ departments: [], doctors: [], slots: [] })
const records = ref<Appointment[]>([])
const error = ref('')
const status = ref('')
const busy = ref(false)
const activeMove = ref<Appointment>()
const confirmingCancellation = ref<number>()
const currentTime = ref(Date.now())
let timeRefresh: number | undefined

function canChange(item: Appointment) {
  return item.status === 'STOPPED' || (item.status === 'BOOKED' && currentTime.value < Date.parse(item.bookingChangesCloseAt))
}

function checkInState(item: Appointment) {
  if (currentTime.value < Date.parse(item.checkInOpensAt)) return '签到尚未开始'
  if (currentTime.value >= Date.parse(item.checkInClosesAt)) return '签到已截止'
  return '现在可以签到'
}
const moveSlots = ref<Slot[]>([])
const moveCursor = ref<number | null>(null)
const moveAvailability = ref<RevisitSlotPage['availability']>()
const moveFilters = ref<RevisitFilters>({})
const moveFollowup = ref<Followup>()
const submittedAssistance = ref<number>()
const pendingAssistance = computed(() => moveFollowup.value?.requests.find(request =>
  request.category === 'REVISIT_ASSISTANCE' && request.status !== 'RESOLVED'))
const moveNeedsAssistance = computed(() => moveAvailability.value === 'FULL' || moveAvailability.value === 'NOT_RELEASED')
const focused = ref<Appointment>()
const recordRows = computed(() => props.appointmentId ? focused.value ? [focused.value] : [] : records.value)
const stopRefresh = ref(0)
const activeCompletion = ref<number>()
const activeCorrection = ref<number>()
interface StopImpact {
  slotId: number; startsAt: string; doctor: string; department: string; alreadyStopped: boolean
  patients: { appointmentId: number; name: string; status: string }[]
}
interface StopResult { slotId: number; status: string; affected: number }
const selectedSlots = ref<number[]>([])
const stopImpact = ref<StopImpact[]>([])
const stopResults = ref<StopResult[]>([])
const historyStatus = ref('')
const historyCursor = ref<number | null>(null)
const activeHandoff = ref<number>()
const handoffOwner = ref(0)
const staff = ref<Choice[]>([])
const reason = ref('')
const note = ref('')
const returnPlan = ref('')
const newSlot = ref({ doctorId: 0, startsAt: '', endsAt: '', capacity: 1, visitLocation: '', preparation: '' })
const newDepartment = ref('')
const newDoctor = ref({ username: '', displayName: '', departmentId: 0 })
const slotDepartment = ref(0)
const slotFrom = ref('')
const slotTo = ref('')
const nextSlot = ref<number | null>(null)
const usableSlots = computed(() => activeMove.value?.followupId ? moveSlots.value : schedule.value.slots.filter((slot) =>
  slot.id !== activeMove.value?.slotId && slot.remaining > 0 && !slot.stoppedAt && slot.available
    && (props.actor.demo || slot.doctorInDepartment)))

async function refresh() {
  const [newSchedule] = await Promise.all([api<Schedule>('/schedule'), loadAppointments()])
  schedule.value = newSchedule
  if (!props.actor.demo) await loadSlots()
  if (!operations && props.appointmentId) focused.value = await api<Appointment>(`/appointments/${props.appointmentId}`)
  if (activeMove.value?.followupId) await loadMoveSlots()
  newSlot.value.doctorId ||= newSchedule.doctors[0]?.id ?? 0
  newDoctor.value.departmentId ||= newSchedule.departments[0]?.id ?? 0
}

async function loadAppointments(afterId?: number) {
  if (!patient) {
    records.value = await api<Appointment[]>('/appointments')
    return
  }
  const query = new URLSearchParams()
  if (historyStatus.value) query.set('status', historyStatus.value)
  if (afterId) query.set('afterId', String(afterId))
  const page = await api<{ appointments: Appointment[]; nextCursor: number | null }>('/appointments/history?' + query)
  records.value = afterId ? [...records.value, ...page.appointments] : page.appointments
  historyCursor.value = page.nextCursor
}

async function filterAppointments(afterId?: number) {
  error.value = ''
  try { await loadAppointments(afterId) }
  catch (failure) { error.value = (failure as Error).message }
}

async function loadSlots(afterId?: number) {
  const query = new URLSearchParams()
  if (slotDepartment.value) query.set('departmentId', String(slotDepartment.value))
  if (slotFrom.value) query.set('from', fromShanghaiInput(slotFrom.value))
  if (slotTo.value) query.set('to', fromShanghaiInput(slotTo.value))
  if (afterId) query.set('afterId', String(afterId))
  const page = await api<{ slots: Schedule['slots']; nextCursor: number | null }>('/schedule-slots?' + query)
  schedule.value.slots = afterId ? [...schedule.value.slots, ...page.slots] : page.slots
  nextSlot.value = page.nextCursor
}

async function filterSlots(afterId?: number) {
  error.value = ''
  try { await loadSlots(afterId) } catch (failure) { error.value = (failure as Error).message }
}

async function run(action: () => Promise<unknown>, done: string) {
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    await action()
    status.value = done
    await refresh()
    stopRefresh.value++
    emit('changed')
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

async function loadMoveSlots(afterId?: number) {
  const appointment = activeMove.value
  if (!appointment?.followupId) return
  const query = revisitQuery(appointment.id, moveFilters.value, afterId)
  const page = await api<RevisitSlotPage>(`/followups/${appointment.followupId}/revisit-slots?${query}`)
  if (activeMove.value?.id !== appointment.id) return
  moveSlots.value = afterId ? [...moveSlots.value, ...page.slots] : page.slots
  moveCursor.value = page.nextCursor
  if (!afterId || !moveSlots.value.length) moveAvailability.value = page.availability
  if (!moveNeedsAssistance.value) return
  moveFollowup.value = undefined
  const task = await api<Followup>(`/followups/${appointment.followupId}`)
  if (activeMove.value === appointment) moveFollowup.value = task
}

function assistanceSubmitted(id: number) {
  submittedAssistance.value = id
  emit('changed')
}

async function filterMoveSlots(filters: RevisitFilters) {
  moveFilters.value = filters
  error.value = ''
  try { await loadMoveSlots() }
  catch (failure) { error.value = (failure as Error).message }
}

async function startMove(item: Appointment) {
  if (!canChange(item)) return
  activeMove.value = item
  moveSlots.value = []
  moveCursor.value = null
  moveAvailability.value = undefined
  moveFollowup.value = undefined
  submittedAssistance.value = undefined
  moveFilters.value = {}
  error.value = ''
  try { await loadMoveSlots() }
  catch (failure) { error.value = (failure as Error).message }
  await nextTick()
  document.getElementById('slots-title')?.scrollIntoView({ block: 'start' })
}

async function submitAppointment(slotId: number) {
  const previous = activeMove.value
  const path = previous ? `/appointments/${previous.id}/reschedule` : '/appointments'
  await run(async () => {
    let updated: { id: number }
    try {
      updated = await mutate(path, { slotId }, { 'Idempotency-Key': crypto.randomUUID() })
    } catch (failure) {
      if (!previous) throw failure
      const current = await api<Appointment>(`/appointments/${previous.id}`)
      if (focused.value?.id === current.id) focused.value = current
      if (previous.followupId) await loadMoveSlots().catch(() => { moveAvailability.value = undefined })
      throw new Error(`${(failure as Error).message} 原预约当前状态为「${states[current.status]}」。`)
    }
    activeMove.value = undefined
    if (previous && props.appointmentId === previous.id) {
      await router.replace({ path: '/workspace', query: { appointment: updated.id } })
    }
  }, previous ? '改约已确认。' : '预约已确认。')
}

function cancelAppointment(id: number) {
  return run(async () => {
    await mutate(`/appointments/${id}/cancel`, undefined, { 'Idempotency-Key': crypto.randomUUID() })
    confirmingCancellation.value = undefined
    if (activeMove.value?.id === id) activeMove.value = undefined
  }, '预约已取消。')
}

function submitSlot() {
  const draft = newSlot.value
  return run(() => mutate('/schedule-slots', {
    doctorId: draft.doctorId, startsAt: fromShanghaiInput(draft.startsAt), endsAt: fromShanghaiInput(draft.endsAt),
    capacity: draft.capacity, visitLocation: draft.visitLocation, preparation: draft.preparation,
  }), '号源已开放。')
}

async function previewStop(ids: number[]) {
  error.value = ''
  stopResults.value = []
  stopImpact.value = []
  reason.value = ''
  try { stopImpact.value = await mutate<StopImpact[]>('/schedule-slots/stop-preview', { slotIds: ids }) }
  catch (failure) { error.value = (failure as Error).message }
}

function confirmStop() {
  return run(async () => {
    stopResults.value = await mutate<StopResult[]>('/schedule-slots/stop-batch', {
      slotIds: stopImpact.value.map(item => item.slotId), reason: reason.value,
    })
    stopImpact.value = []
    selectedSlots.value = []
  }, '停诊处理已完成。')
}

async function copySlot(slot: Slot) {
  newSlot.value = {
    doctorId: slot.doctorId, startsAt: toShanghaiInput(slot.startsAt), endsAt: toShanghaiInput(slot.endsAt),
    capacity: slot.capacity, visitLocation: slot.visitLocation ?? '', preparation: slot.preparation ?? '',
  }
  await nextTick()
  document.getElementById('new-slot-start')?.focus()
}
function reportCorrection(id: number) { activeCorrection.value = id; reason.value = '' }

async function chooseHandoff(id: number) {
  try {
    staff.value = (await api<Directory>('/care-directory')).owners
    handoffOwner.value = staff.value[0]?.id ?? 0
    activeHandoff.value = id
  } catch (failure) { error.value = (failure as Error).message }
}

function submitHandoff(id: number) {
  return run(async () => {
    await mutate(`/appointments/${id}/return-plan`, { ownerId: handoffOwner.value })
    activeHandoff.value = undefined
  }, '门诊复诊安排已交接。')
}

async function refreshWithFeedback() {
  try { await refresh() } catch (failure) { error.value = (failure as Error).message }
}

onMounted(() => {
  void refreshWithFeedback()
  if (patient) timeRefresh = window.setInterval(() => { currentTime.value = Date.now() }, 1000)
})
onUnmounted(() => window.clearInterval(timeRefresh))
watch(() => props.refreshVersion, refreshWithFeedback)
watch(() => props.appointmentId, async (id) => {
  if (operations) return
  focused.value = undefined
  activeMove.value = undefined
  confirmingCancellation.value = undefined
  error.value = ''
  if (!id) return
  try {
    const appointment = await api<Appointment>(`/appointments/${id}`)
    if (props.appointmentId !== id) return
    focused.value = appointment
  } catch (failure) {
    if (props.appointmentId !== id) return
    error.value = (failure as Error).message
  }
  await nextTick()
  const panel = document.getElementById('selected-appointment')
  panel?.focus({ preventScroll: true })
  panel?.scrollIntoView({ block: 'start' })
}, { immediate: true })
</script>

<template>
  <div id="schedule-workspace" class="workspace-section">
    <p v-if="error && !activeMove" role="alert" class="error">{{ error }}</p>
    <p v-if="status" role="status">{{ status }}</p>
    <form v-if="!actor.demo && (patient || operations)" class="panel field-grid" @submit.prevent="filterSlots()">
      <label>排班科室<select v-model.number="slotDepartment">
        <option :value="0">全部科室</option>
        <option v-for="item in schedule.departments" :key="item.id" :value="item.id">{{ item.name }}</option>
      </select></label>
      <label>起始日期时间<input v-model="slotFrom" type="datetime-local" /></label>
      <label>结束日期时间<input v-model="slotTo" type="datetime-local" /></label>
      <button type="submit">筛选排班</button>
      <button v-if="nextSlot" class="secondary" type="button" @click="filterSlots(nextSlot!)">载入更多排班</button>
    </form>
    <section v-if="operations" class="panel" aria-labelledby="manage-title">
      <h2 id="manage-title">排班与放号</h2>
      <form class="field-grid" @submit.prevent="submitSlot">
        <label>医生<select v-model.number="newSlot.doctorId" required><option v-for="person in schedule.doctors" :key="person.id" :value="person.id">{{ person.displayName }}</option></select></label>
        <label>开始时间（北京时间）<input id="new-slot-start" v-model="newSlot.startsAt" type="datetime-local" required /></label>
        <label>结束时间（北京时间）<input v-model="newSlot.endsAt" type="datetime-local" required /></label>
        <label>号源数量<input v-model.number="newSlot.capacity" min="1" max="1000" type="number" required /></label>
        <label>就诊地点<input v-model.trim="newSlot.visitLocation" maxlength="200" /></label>
        <label>就诊准备事项<textarea v-model.trim="newSlot.preparation" maxlength="1000" /></label>
        <button :disabled="busy || !newSlot.doctorId">开放号源</button>
      </form>
      <details v-if="actor.demo">
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
      <button v-if="selectedSlots.length" class="secondary" type="button" @click="previewStop(selectedSlots)">
        预览批量停诊（{{ selectedSlots.length }}）
      </button>
      <section v-if="stopImpact.length" aria-label="停诊影响预览" class="panel">
        <h3>停诊影响预览</h3>
        <p>可能影响 {{ stopImpact.reduce((total, item) => total + item.patients.length, 0) }} 笔预约</p>
        <ul class="source-list">
          <li v-for="item in [...stopImpact].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))" :key="item.slotId">
            {{ shanghaiTime(item.startsAt) }} · {{ item.department }} · {{ item.doctor }} · {{ item.alreadyStopped ? '已停诊' : item.patients.length + ' 笔预约' }}
            <span v-if="item.patients.length"> · {{ item.patients.map(patient => patient.name).join('、') }}</span>
          </li>
        </ul>
        <form class="field-grid" @submit.prevent="confirmStop">
          <label>停诊原因<input v-model.trim="reason" maxlength="400" required /></label>
          <button :disabled="busy">确认停诊</button>
          <button type="button" class="secondary" @click="stopImpact = []">返回排班</button>
        </form>
      </section>
      <ul v-if="stopResults.length" class="source-list" aria-label="停诊处理结果">
        <li v-for="item in stopResults" :key="item.slotId">
          排班 {{ item.slotId }} · {{ item.status === 'ALREADY_STOPPED' ? '此前已停诊' : '已停诊 · 纳入跟进 ' + item.affected + ' 笔预约' }}
        </li>
      </ul>
      <ul class="record-list">
        <li v-for="slot in schedule.slots" :key="slot.id" class="record" :data-schedule-id="slot.id">
          <div><strong>{{ slot.doctorName }} · {{ slot.department }}</strong><div>{{ shanghaiTime(slot.startsAt) }}</div><div>{{ slot.stoppedAt ? `已停诊 · ${slot.stopReason}` : `剩余 ${slot.remaining} 个号源` }}</div></div>
          <div class="row-actions">
            <label v-if="!slot.stoppedAt"><input type="checkbox" v-model="selectedSlots" :value="slot.id" /> 选入批量停诊</label>
            <button class="secondary" type="button" @click="copySlot(slot)">复制排班</button>
            <button v-if="!slot.stoppedAt" class="secondary" type="button" @click="previewStop([slot.id])">停诊</button>
          </div>
        </li>
      </ul>
    </section>
    <section v-if="patient" class="panel" aria-labelledby="slots-title">
      <h2 id="slots-title">{{ activeMove ? '选择新的时段' : '可预约时段' }}</h2>
      <p v-if="activeMove && error" role="alert" class="error">{{ error }}</p>
      <p v-if="activeMove?.status === 'BOOKED'">原预约保留到改约成功。</p>
      <p v-if="activeMove"><button class="secondary" type="button" @click="activeMove = undefined">返回我的预约</button></p>
      <RevisitSlotFilters v-if="activeMove?.followupId" :key="activeMove.id" @apply="filterMoveSlots" />
      <p v-if="!usableSlots.length && !activeMove?.followupId">暂无符合要求的可预约号源。</p>
      <p v-if="activeMove?.followupId && moveAvailability && !usableSlots.length">{{ noRevisitSlots[moveAvailability] }}</p>
      <template v-if="activeMove && moveFollowup && moveNeedsAssistance">
        <div v-if="pendingAssistance || submittedAssistance" class="row-actions">
          <p role="status">复诊协助 · {{ requestStates[pendingAssistance?.status ?? 'SUBMITTED'] }}</p>
          <RouterLink :to="{ path: '/workspace', query: {
            followup: moveFollowup.id, request: pendingAssistance?.id ?? submittedAssistance,
          } }">查看协助进度</RouterLink>
        </div>
        <RevisitAssistanceForm v-else-if="!moveFollowup.needsReview && canChange(activeMove)" :task-id="moveFollowup.id" :version="moveFollowup.version"
          :appointment-id="activeMove.id" :disabled="busy" @submitted="assistanceSubmitted" />
      </template>
      <button v-if="activeMove?.followupId && moveCursor" class="secondary" type="button"
        @click="loadMoveSlots(moveCursor).catch(failure => error = failure.message)">载入更多复诊号源</button>
      <ul class="record-list">
        <li v-for="slot in usableSlots" :key="slot.id" class="record">
          <div><strong>{{ slot.doctorName }} · {{ slot.department }}</strong><div>{{ shanghaiTime(slot.startsAt) }}</div><div>剩余 {{ slot.remaining }} 个号源</div></div>
          <button :disabled="busy" type="button" @click="submitAppointment(slot.id)">{{ activeMove ? '确认改约' : '预约此时段' }}</button>
        </li>
      </ul>
    </section>
    <StopFollowups v-if="operations" :appointment-id="appointmentId" :refresh-version="stopRefresh + refreshVersion"
      :actor-id="actor.id" @changed="emit('changed')" />
    <section v-if="patient || doctor" id="selected-appointment" tabindex="-1" class="panel" aria-labelledby="appointment-title">
      <div class="section-heading">
        <h2 id="appointment-title">{{ patient ? '我的预约' : '接诊列表' }}</h2>
        <RouterLink v-if="appointmentId" :to="{ path: '/workspace' }">查看全部预约</RouterLink>
      </div>
      <form v-if="patient && !appointmentId" class="field-grid" @submit.prevent="filterAppointments()">
        <label>预约历史状态<select v-model="historyStatus">
          <option value="">全部预约</option><option v-for="(label, key) in states" :key="key" :value="key">{{ label }}</option>
        </select></label>
        <button type="submit">筛选预约历史</button>
      </form>
      <p v-if="appointmentId && error && !focused" class="error">{{ error }}</p>
      <p v-else-if="!recordRows.length">暂无记录。</p>
      <ul class="record-list">
        <li v-for="item in recordRows" :key="item.id" class="record" :data-appointment-id="item.id">
          <div>
            <strong>{{ patient ? item.doctor : item.patientName }} · {{ states[item.status] }}</strong>
            <div>{{ shanghaiTime(item.startsAt) }} · {{ item.department }}</div>
            <div v-if="patient && item.visitLocation">就诊地点 · {{ item.visitLocation }}</div>
            <div v-if="patient && item.preparation" class="preserve-lines">就诊准备 · {{ item.preparation }}</div>
            <template v-if="patient && item.status === 'BOOKED'">
              <div><strong>{{ checkInState(item) }}</strong>（北京时间）</div>
              <div>签到窗口 · {{ shanghaiTimeOnVisitDay(item.checkInOpensAt, item.startsAt) }} 至 {{ shanghaiTimeOnVisitDay(item.checkInClosesAt, item.startsAt) }} 前</div>
              <div>退改约截止 · {{ shanghaiTimeOnVisitDay(item.bookingChangesCloseAt, item.startsAt) }} 前<span v-if="!canChange(item)">（已截止）</span></div>
            </template>
            <div v-if="patient && item.status === 'CHECKED_IN'">已签到，请等待接诊。签到后无法在线退改约。</div>
            <div v-if="item.status === 'STOPPED'">{{ item.stopReason }}</div>
            <div v-if="patient && item.status === 'STOPPED'">停诊后仍可改约或取消，无需签到。</div>
            <StopCallbackProgress v-if="patient && appointmentId === item.id && item.stopReason" :appointment-id="item.id" />
          </div>
          <div class="row-actions">
            <template v-if="patient && ['BOOKED', 'STOPPED'].includes(item.status)">
              <button type="button" v-if="item.status === 'BOOKED'" :disabled="busy || checkInState(item) !== '现在可以签到'"
                class="secondary" @click="run(() => mutate(`/appointments/${item.id}/check-in`), '签到已确认。')">签到</button>
              <button type="button" class="secondary" :disabled="busy || !canChange(item)" @click="startMove(item)">改约</button>
              <button v-if="confirmingCancellation !== item.id" type="button" :disabled="busy || !canChange(item)"
                class="secondary" @click="confirmingCancellation = item.id">取消预约</button>
            </template>
            <button v-if="doctor && item.status === 'CHECKED_IN'" :disabled="busy" type="button" @click="run(() => mutate(`/appointments/${item.id}/start`), '已开始接诊。')">开始接诊</button>
            <button v-if="doctor && item.status === 'IN_CONSULT' && activeCompletion !== item.id" type="button" @click="activeCompletion = item.id; note = ''; returnPlan = ''">录入接诊结果</button>
            <button v-if="doctor && item.status === 'NO_SHOW' && activeCorrection !== item.id" type="button" class="secondary" @click="reportCorrection(item.id)">更正签到</button>
            <button v-if="doctor && item.status === 'COMPLETED' && activeHandoff !== item.id" type="button" class="secondary" @click="chooseHandoff(item.id)">交接门诊复诊</button>
          </div>
          <div v-if="confirmingCancellation === item.id && canChange(item)" class="message-detail">
            <p>取消后，这笔预约将失效。需要就诊时请重新预约。</p>
            <div class="row-actions">
              <button type="button" :disabled="busy" @click="cancelAppointment(item.id)">确认取消预约</button>
              <button type="button" class="secondary" @click="confirmingCancellation = undefined">保留原预约</button>
            </div>
          </div>
          <form v-if="activeCompletion === item.id" class="field-grid" @submit.prevent="run(async () => { await mutate(`/appointments/${item.id}/complete`, { note, returnPlan }); activeCompletion = undefined }, '接诊已完成。')">
            <label>模拟接诊记录<textarea v-model.trim="note" maxlength="4000" required /></label>
            <label>复诊安排<textarea v-model.trim="returnPlan" maxlength="2000" /></label>
            <button :disabled="busy">确认完成</button>
          </form>
          <form v-if="activeCorrection === item.id" class="field-grid" @submit.prevent="run(async () => { await mutate(`/appointments/${item.id}/correct-attendance`, { reason }); activeCorrection = undefined }, '签到已更正。')">
            <label>更正原因<input v-model.trim="reason" maxlength="400" required /></label><button :disabled="busy">确认更正</button>
          </form>
          <form v-if="activeHandoff === item.id" class="field-grid" @submit.prevent="submitHandoff(item.id)">
            <label>随访接手人<select v-model.number="handoffOwner" required><option v-for="person in staff" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
            <button :disabled="busy || !handoffOwner">确认交接</button>
          </form>
        </li>
      </ul>
      <button v-if="patient && !appointmentId && historyCursor" class="secondary" type="button"
        @click="filterAppointments(historyCursor)">载入更多预约</button>
    </section>
  </div>
</template>
