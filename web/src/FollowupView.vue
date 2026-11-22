<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, mutate, type Actor } from './api'
import { appointmentStates, noRevisitSlots, revisitQuery, type RevisitFilters, type RevisitSlotPage, type Schedule, type Slot } from './appointments'
import RevisitSlotFilters from './RevisitSlotFilters.vue'
import { feedbackKinds, followupStates, requestOutcomes, type CareEpisode, type Directory, type DocumentVersion, type Followup, type Revisit, type Source } from './followup'
import { fromShanghaiInput, shanghaiTime } from './time'
import AgentSuggestions, { type Candidate } from './AgentSuggestions.vue'
import FollowupRequests from './FollowupRequests.vue'
import RevisionReview from './RevisionReview.vue'
import SourceCitations from './SourceCitations.vue'
import DepartmentTemplates from './DepartmentTemplates.vue'

const props = defineProps<{ actor: Actor; refreshVersion: number; taskId?: number; requestId?: number; episodeTarget?: number; revisitAppointmentId?: number; progressTarget?: boolean }>()
const emit = defineEmits<{ booked: []; changed: [] }>()
const patient = props.actor.roles.includes('PATIENT')
const followup = props.actor.roles.includes('FOLLOWUP')
const reviewer = props.actor.roles.includes('REVIEWER')
const episodes = ref<CareEpisode[]>([])
const tasks = ref<Followup[]>([])
const historyClosed = ref('')
const historyKind = ref('')
const historyCursor = ref<number | null>(null)
const directory = ref<Directory>({ patients: [], reviewers: [], owners: [], departments: [] })
const departments = ref<Schedule['departments']>([])
const episodeId = ref(0)
const documents = ref<DocumentVersion[]>([])
const sources = ref<Source[]>([])
const detail = ref<Followup>()
const editing = ref<number>()
const error = ref('')
const notice = ref('')
const bookingError = ref('')
const bookingNotice = ref('')
const busy = ref(false)
const discharge = ref({ patientId: 0, departmentId: 0, reference: '', careDate: '' })
const doc = ref({ documentId: 0, title: '', text: '' })
const selectedFile = ref<File>()
const fileKey = ref(0)
const visits = ref<Revisit[]>([])
const slots = ref<Slot[]>([])
const nextSlot = ref<number | null>(null)
const availability = ref<RevisitSlotPage['availability']>()
interface RevisitWatch { id: number | null; status: 'WAITING' | 'NOTIFIED' | 'CANCELLED' | 'NONE' }
const slotWatch = ref<RevisitWatch>()
const filters = ref<RevisitFilters>({})
let taskLoad = 0
const revisitSlot = ref(0)
const draftTemplates = [
  { title: '复诊安排', kind: 'REVISIT', instructions: '请按已确认的科室和日期范围预约复诊。' },
  { title: '跟进待出检查结果', kind: 'PENDING_RESULT', instructions: '医护确认进展后会在本随访更新。' },
]
const draft = ref({ reviewerId: 0, kind: 'REVISIT', title: '', instructions: '', dueAt: '', revisitFrom: '', revisitTo: '', revisitDepartmentId: 0, segmentIds: [] as number[], manualReference: '' })
const action = ref({ name: '', note: '', newOwnerId: 0, closureReason: 'UNREACHABLE', encounterId: 0 })
const currentDocuments = computed(() => documents.value.filter((row, index, rows) => rows.findIndex((item) => item.id === row.id) === index))
const active = computed(() => !!detail.value && ['DISPATCHED', 'PATIENT_REPLIED'].includes(detail.value.status))
const canEdit = computed(() => !!detail.value && ['DRAFT', 'APPROVED'].includes(detail.value.status) && detail.value.ownerId === props.actor.id)
const canManage = computed(() => !!detail.value && !['VERIFIED', 'CLOSED'].includes(detail.value.status) && detail.value.ownerId === props.actor.id)
const canReview = computed(() => !!detail.value && reviewer && detail.value.reviewerId === props.actor.id)
const selectedEpisode = computed(() => episodes.value.find((item) => item.id === episodeId.value))
const sourceVersion = computed(() => selectedEpisode.value?.sourceVersion ?? 0)
const selectedReply = computed(() => patient && props.taskId === detail.value?.id && props.requestId
  ? detail.value?.requests.find(item => item.id === props.requestId && item.category === 'REVISIT_ASSISTANCE' && item.publicReply)
  : undefined)
const changeVisit = computed(() => {
  const assistance = detail.value?.requests.find(item => item.category === 'REVISIT_ASSISTANCE' && item.status !== 'RESOLVED')
  const originalId = (props.taskId === detail.value?.id ? props.revisitAppointmentId : undefined)
    ?? selectedReply.value?.appointmentId ?? assistance?.appointmentId
  return visits.value.find(item => item.status === 'STOPPED'
    || (item.status === 'BOOKED' && Date.now() < Date.parse(item.bookingChangesCloseAt)
      && (item.needsReschedule || item.appointmentId === originalId)))
})
const allowRevisit = computed(() => patient && active.value && !visits.value.some((item) =>
  ['BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED', 'STOPPED'].includes(item.status)))
const revisitPlanning = computed(() => active.value && (allowRevisit.value || !!changeVisit.value))
const canAsk = computed(() => patient && revisitPlanning.value && (availability.value === 'FULL' || availability.value === 'NOT_RELEASED'))

async function refresh() {
  if (patient) await loadHistory()
  else tasks.value = await api<Followup[]>('/followups')
  if (!patient) {
    const [rows, choices, schedule] = await Promise.all([
      api<CareEpisode[]>('/episodes'), api<Directory>('/care-directory'),
      followup ? api<Schedule>('/schedule') : null,
    ])
    episodes.value = rows
    directory.value = choices
    if (schedule) departments.value = schedule.departments
    draft.value.reviewerId ||= choices.reviewers[0]?.id ?? 0
    discharge.value.patientId ||= choices.patients[0]?.id ?? 0
    discharge.value.departmentId ||= choices.departments[0]?.id ?? 0
  }
}

async function loadHistory(afterId?: number) {
  const query = new URLSearchParams()
  if (historyClosed.value) query.set('closed', historyClosed.value)
  if (historyKind.value) query.set('kind', historyKind.value)
  if (afterId) query.set('afterId', String(afterId))
  try {
    const page = await api<{ tasks: Followup[]; nextCursor: number | null }>('/followups/history?' + query)
    tasks.value = afterId ? [...tasks.value, ...page.tasks] : page.tasks
    historyCursor.value = page.nextCursor
  } catch (failure) { error.value = (failure as Error).message }
}

async function run(write: () => Promise<unknown>, message: string) {
  error.value = ''
  notice.value = ''
  busy.value = true
  try { await write(); notice.value = message; await refresh(); emit('changed') }
  catch (failure) { error.value = (failure as Error).message }
  finally { busy.value = false }
}

async function chooseEpisode(id: number) {
  if (episodeId.value !== id) draft.value.segmentIds = []
  episodeId.value = id
  if (!id) { documents.value = []; sources.value = []; return }
  try {
    const [files, snippets] = await Promise.all([
      api<DocumentVersion[]>(`/episodes/${id}/documents`), api<Source[]>(`/episodes/${id}/segments`),
    ])
    documents.value = files
    sources.value = snippets
  } catch (failure) { error.value = (failure as Error).message }
}

function addEpisode() {
  return run(async () => {
    const created = await mutate<{ id: number }>('/episodes', discharge.value)
    discharge.value.reference = ''
    await chooseEpisode(created.id)
  }, '出院记录已登记。')
}

function addDocument() {
  return run(async () => {
    const path = `/episodes/${episodeId.value}/documents`
    if (selectedFile.value) {
      const body = new FormData()
      body.set('title', doc.value.title || selectedFile.value.name)
      if (doc.value.documentId) body.set('documentId', String(doc.value.documentId))
      body.set('file', selectedFile.value)
      await mutate(path, body)
    } else {
      await mutate(path, { ...doc.value, documentId: doc.value.documentId || null })
    }
    doc.value = { documentId: 0, title: '', text: '' }
    selectedFile.value = undefined
    fileKey.value++
    await chooseEpisode(episodeId.value)
    if (detail.value) detail.value = await api<Followup>(`/followups/${detail.value.id}`)
  }, '资料版本已保存。')
}

async function loadRevisitSlots(afterId?: number) {
  const id = detail.value?.id
  if (!id) return
  const query = revisitQuery(changeVisit.value?.appointmentId, filters.value, afterId)
  const page = await api<RevisitSlotPage>(`/followups/${id}/revisit-slots?${query}`)
  if (detail.value?.id !== id) return
  slots.value = afterId ? [...slots.value, ...page.slots] : page.slots
  nextSlot.value = page.nextCursor
  if (!afterId || !slots.value.length) availability.value = page.availability
  if (!afterId) revisitSlot.value = slots.value[0]?.id ?? 0
}

async function applyRevisitFilters(value: RevisitFilters) {
  filters.value = value
  bookingError.value = ''
  try { await loadRevisitSlots() }
  catch (failure) { bookingError.value = (failure as Error).message }
}

async function showTask(id: number) {
  const load = ++taskLoad
  error.value = ''
  if (detail.value?.id !== id) {
    detail.value = undefined
    bookingError.value = ''
    bookingNotice.value = ''
    filters.value = {}
  }
  action.value.name = ''
  action.value.encounterId = 0
  availability.value = undefined
  slotWatch.value = undefined
  slots.value = []
  nextSlot.value = null
  try {
    const current = await api<Followup>(`/followups/${id}`)
    const related = current.kind === 'REVISIT' ? await api<Revisit[]>(`/followups/${id}/appointments`) : []
    if (load !== taskLoad) return false
    detail.value = current
    visits.value = related
    if (patient && current.kind === 'REVISIT' && revisitPlanning.value) {
      const [, state] = await Promise.all([loadRevisitSlots(), api<RevisitWatch>(`/followups/${id}/revisit-watch`)])
      if (load === taskLoad) slotWatch.value = state
    }
    return load === taskLoad
  } catch (failure) {
    if (load === taskLoad) error.value = (failure as Error).message
    return false
  }
}

function applyDraftTemplate(template: typeof draftTemplates[number]) {
  draft.value.kind = template.kind
  draft.value.title = template.title
  draft.value.instructions = template.instructions
}

async function edit(item: Followup) {
  await chooseEpisode(item.episodeId)
  editing.value = item.id
  const shanghaiDate = new Date(new Date(item.dueAt).getTime() + 8 * 3_600_000)
  draft.value = { reviewerId: item.reviewerId, kind: item.kind, title: item.title,
    instructions: item.instructions ?? '', dueAt: shanghaiDate.toISOString().slice(0, 16),
    revisitFrom: item.revisitFrom ?? '', revisitTo: item.revisitTo ?? '',
    revisitDepartmentId: item.revisitDepartmentId ?? 0,
    segmentIds: item.sources.map((source) => source.id).filter((id) => sources.value.some((source) => source.id === id)),
    manualReference: item.manualReference ?? '' }
}

function saveDraft() {
  return run(async () => {
    const isRevisit = draft.value.kind === 'REVISIT'
    const data = { kind: draft.value.kind, title: draft.value.title, instructions: draft.value.instructions,
      dueAt: fromShanghaiInput(draft.value.dueAt), segmentIds: draft.value.segmentIds,
      manualReference: draft.value.manualReference || null,
      revisitFrom: isRevisit ? draft.value.revisitFrom : null,
      revisitTo: isRevisit ? draft.value.revisitTo : null,
      revisitDepartmentId: isRevisit ? draft.value.revisitDepartmentId : null }
    if (editing.value && detail.value) {
      await mutate(`/followups/${editing.value}/draft`, { version: detail.value.version, draft: data })
      await showTask(editing.value)
      editing.value = undefined
    } else {
      const result = await mutate<{ id: number }>('/followups', { episodeId: episodeId.value, reviewerId: draft.value.reviewerId, draft: data })
      await showTask(result.id)
    }
    draft.value.title = ''
    draft.value.instructions = ''
    draft.value.manualReference = ''
    draft.value.segmentIds = []
    draft.value.revisitFrom = ''
    draft.value.revisitTo = ''
    draft.value.revisitDepartmentId = 0
  }, '草稿已保存。')
}

function adopt(candidate: Candidate) {
  const previous = draft.value
  const isRevisit = candidate.kind === 'REVISIT'
  draft.value = { reviewerId: previous.reviewerId, kind: candidate.kind, title: candidate.title,
    instructions: candidate.instructions,
    dueAt: !isRevisit && candidate.dueDate ? candidate.dueDate + 'T17:00' : '',
    revisitFrom: isRevisit ? candidate.dueDate ?? '' : '', revisitTo: isRevisit ? candidate.dueDate ?? '' : '',
    revisitDepartmentId: 0,
    segmentIds: [...new Set(candidate.references.map((item) => item.segmentId))], manualReference: '' }
}

function apply(name: string) {
  const item = detail.value
  if (!item) return
  return run(async () => {
    await mutate(`/followups/${item.id}/actions`, { action: name, version: item.version, note: action.value.note || null,
      newOwnerId: action.value.newOwnerId || null, closureReason: name === 'CLOSE' ? action.value.closureReason : null,
      encounterId: name === 'VERIFY' ? action.value.encounterId || null : null })
    action.value.note = ''
    if (name === 'TRANSFER') detail.value = undefined
    else await showTask(item.id)
  }, '随访已更新。')
}

function bookRevisit(id: number) {
  return run(async () => {
    await mutate(`/followups/${id}/appointments`, { slotId: revisitSlot.value }, { 'Idempotency-Key': crypto.randomUUID() })
    await showTask(id)
    emit('booked')
  }, '复诊预约已关联到此随访。')
}

async function toggleSlotWatch(id: number) {
  busy.value = true
  bookingError.value = ''
  const path = `/followups/${id}/revisit-watch`
  try {
    if (slotWatch.value?.status === 'WAITING') {
      await mutate(path + '/cancel')
      slotWatch.value = { id: null, status: 'CANCELLED' }
    } else {
      slotWatch.value = await mutate<RevisitWatch>(path, { appointmentId: changeVisit.value?.appointmentId })
    }
  } catch (failure) { bookingError.value = (failure as Error).message }
  finally { busy.value = false }
}

async function confirmReschedule(id: number, originalId: number) {
  busy.value = true
  bookingError.value = ''
  bookingNotice.value = ''
  try {
    await mutate(`/appointments/${originalId}/reschedule`, { slotId: revisitSlot.value },
      { 'Idempotency-Key': crypto.randomUUID() })
    if (detail.value?.id === id) {
      await showTask(id)
      bookingNotice.value = '改约已确认，新预约已关联到此随访。'
    }
    emit('booked')
  } catch (failure) {
    if (detail.value?.id !== id) return
    const loaded = await showTask(id)
    if (detail.value?.id !== id) return
    const original = loaded ? visits.value.find(item => item.appointmentId === originalId) : undefined
    bookingError.value = (failure as Error).message + (original
      ? ` 原预约当前状态为「${appointmentStates[original.status]}」。`
      : ' 请刷新随访，核对原预约状态。')
  } finally { busy.value = false }
}

onMounted(async () => {
  try { await refresh() } catch (failure) { error.value = (failure as Error).message }
})
watch(() => props.refreshVersion, () => {
  if (detail.value) void showTask(detail.value.id)
})
watch([() => props.taskId, () => props.requestId, () => props.revisitAppointmentId, () => props.progressTarget],
  async ([id, requestId, revisitAppointmentId, progressTarget], previous) => {
  if (!id) {
    if (previous?.[0]) { ++taskLoad; detail.value = undefined; error.value = '' }
    return
  }
  await showTask(id)
  if (props.taskId !== id) return
  await nextTick()
  const panel = (requestId && document.getElementById('followup-request-' + requestId))
    || (revisitAppointmentId && document.getElementById('revisit-booking'))
    || (progressTarget && document.getElementById('followup-result-progress'))
    || document.getElementById('followup-target')
  panel?.focus({ preventScroll: true })
  panel?.scrollIntoView({ block: 'start' })
}, { immediate: true })
watch(() => props.episodeTarget, async (id) => {
  if (!id || !followup) return
  await chooseEpisode(id)
  await nextTick()
  document.getElementById('episode-work')?.scrollIntoView({ block: 'start' })
}, { immediate: true })

async function requestChanged() {
  if (detail.value) await showTask(detail.value.id)
  try { await refresh() } catch (failure) { error.value = (failure as Error).message }
  emit('changed')
}
</script>

<template>
  <section class="panel" aria-labelledby="followup-title">
    <div class="section-heading"><h2 id="followup-title">{{ patient ? '我的随访' : '随访工作台' }}</h2><button type="button" class="secondary" @click="refresh">刷新</button></div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="patient">留言由医护在工作日 09:00–17:00（北京时间）查看。紧急情况请使用当地急救服务。</p>
    <div v-if="followup" class="followup-forms">
      <details>
        <summary>登记合成出院记录</summary>
        <form class="field-grid" @submit.prevent="addEpisode">
          <label>患者<select v-model.number="discharge.patientId" required><option v-for="item in directory.patients" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label>资料科室<select v-model.number="discharge.departmentId" required><option v-for="item in directory.departments" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label>出院记录编号<input v-model.trim="discharge.reference" maxlength="100" required /></label>
          <label>出院日期<input v-model="discharge.careDate" type="date" required /></label>
          <button :disabled="busy">登记出院记录</button>
        </form>
      </details>
      <label class="episode-selection">诊疗记录<select :value="episodeId" @change="chooseEpisode(Number(($event.target as HTMLSelectElement).value))"><option :value="0">请选择诊疗记录</option><option v-for="item in episodes" :key="item.id" :value="item.id">{{ item.patient }} · {{ item.reference }}</option></select></label>
      <template v-if="episodeId">
        <details>
          <summary>导入和更正资料</summary>
          <form class="field-grid" @submit.prevent="addDocument">
            <label>资料操作<select v-model.number="doc.documentId"><option :value="0">新增资料</option><option v-for="item in currentDocuments" :key="item.id" :value="item.id">更正 {{ item.title }}</option></select></label>
            <label>资料标题<input v-model.trim="doc.title" maxlength="200" :required="!selectedFile" /></label>
            <label>UTF-8 文本或文本 PDF<input :key="fileKey" type="file" accept=".txt,.pdf" @change="selectedFile = ($event.target as HTMLInputElement).files?.[0]" /></label>
            <label v-if="!selectedFile">手工录入原文<textarea v-model="doc.text" required /></label>
            <button :disabled="busy">保存资料版本</button>
          </form>
          <ul class="record-list"><li v-for="item in documents" :key="item.versionId" class="record"><span>{{ item.title }} · 版本 {{ item.versionNumber }}</span><a :href="`/api/v1/episodes/${episodeId}/versions/${item.versionId}/download`">下载附件</a></li></ul>
        </details>
        <div id="episode-work"><AgentSuggestions :episode-id="episodeId" :source-version="sourceVersion" @use="adopt" /></div>
        <form class="panel draft-form" @submit.prevent="saveDraft">
          <h3>{{ editing ? `更正随访草稿 ${editing}` : '新建随访草稿' }}</h3>
          <details v-if="!editing">
            <summary>使用随访草稿模板</summary>
            <div class="row-actions">
              <button v-for="template in draftTemplates" :key="template.kind" type="button" class="secondary"
                @click="applyDraftTemplate(template)">{{ template.title }}</button>
            </div>
          </details>
          <DepartmentTemplates v-if="selectedEpisode && !editing" :department-id="selectedEpisode.departmentId"
            :kind="draft.kind" :initial-name="draft.title" :content="draft.instructions" allow-drafts
            @apply="template => applyDraftTemplate({ title: template.name, kind: template.kind, instructions: template.content })" />
          <div class="field-grid">
            <label v-if="!editing">审核医生<select v-model.number="draft.reviewerId" required><option v-for="item in directory.reviewers" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
            <label>随访类型<select v-model="draft.kind"><option value="REVISIT">复诊</option><option value="PENDING_RESULT">待出检查结果</option></select></label>
            <label>事项标题<input v-model.trim="draft.title" maxlength="140" required /></label>
            <template v-if="draft.kind === 'REVISIT'">
              <label>复诊科室<select v-model.number="draft.revisitDepartmentId" required><option :value="0" disabled>选择科室</option><option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
              <label>复诊开始日期<input v-model="draft.revisitFrom" type="date" required /></label>
              <label>复诊结束日期<input v-model="draft.revisitTo" type="date" :min="draft.revisitFrom" required /></label>
            </template>
            <label>完成期限（北京时间）<input v-model="draft.dueAt" type="datetime-local" required /></label>
            <label>安排内容<textarea v-model.trim="draft.instructions" maxlength="4000" required /></label>
            <label>手工记录依据<textarea v-model.trim="draft.manualReference" maxlength="600" /></label>
          </div>
          <details v-if="sources.length"><summary>选择资料出处（已选 {{ draft.segmentIds.length }}）</summary><ul class="source-list"><li v-for="item in sources" :key="item.id"><label><input v-model="draft.segmentIds" :value="item.id" type="checkbox" /> {{ item.title }} · 版本 {{ item.versionNumber }}<span v-if="item.pageNumber"> · 第 {{ item.pageNumber }} 页</span> · 第 {{ item.lineNumber }} 行 · {{ item.excerpt }}</label></li></ul></details>
          <button :disabled="busy">保存随访草稿</button>
        </form>
      </template>
    </div>
    <form v-if="patient" class="field-grid" @submit.prevent="loadHistory()">
      <label>随访历史状态<select v-model="historyClosed">
        <option value="">全部随访</option><option value="false">待处理</option><option value="true">已结束</option>
      </select></label>
      <label>随访历史类型<select v-model="historyKind">
        <option value="">全部类型</option><option value="REVISIT">复诊</option><option value="PENDING_RESULT">待出检查结果</option>
      </select></label>
      <button type="submit">筛选随访历史</button>
    </form>
    <p v-if="!tasks.length">暂无随访任务。</p>
    <ul class="record-list">
      <li v-for="item in tasks" :key="item.id" class="record" :data-followup-id="item.id">
        <div><strong>{{ item.title }} · {{ followupStates[item.status] }}</strong><div>{{ patient ? '完成期限' : item.patient }} · {{ shanghaiTime(item.dueAt) }}</div></div>
        <button type="button" class="secondary" @click="showTask(item.id)">查看随访</button>
      </li>
    </ul>
    <button v-if="patient && historyCursor" class="secondary" type="button" @click="loadHistory(historyCursor)">载入更多随访</button>
    <p v-if="taskId && !detail && error" role="alert" class="error">{{ error }}</p>
    <article v-if="detail" id="followup-target" tabindex="-1" class="panel followup-detail">
      <RouterLink v-if="taskId" :to="{ path: '/workspace' }">返回工作区</RouterLink>
      <h3>{{ detail.title }}</h3>
      <p>{{ followupStates[detail.status] }} · 完成期限 {{ shanghaiTime(detail.dueAt) }}</p>
      <p v-if="detail.needsReview">资料已更正，等待医护确认。</p>
      <p v-if="detail.revisitFrom && (!patient || !detail.needsReview)">
        复诊安排 · {{ detail.revisitDepartment }} · {{ detail.revisitFrom }}<template v-if="detail.revisitFrom !== detail.revisitTo"> 至 {{ detail.revisitTo }}</template>
      </p>
      <AgentSuggestions v-if="canReview" :episode-id="detail.episodeId"
        :source-version="episodes.find((item) => item.id === detail?.episodeId)?.sourceVersion ?? 0" review-only />
      <RevisionReview v-if="detail.previous" :current="detail" :previous="detail.previous" :visits="visits" />
      <template v-else>
        <p v-if="detail.instructions" class="preserve-lines">{{ detail.instructions }}</p>
        <p v-if="detail.manualReference">手工依据 · {{ detail.manualReference }}</p>
        <SourceCitations :episode-id="detail.episodeId" :sources="detail.sources" />
      </template>
      <ul v-if="detail.reviews.length" class="source-list"><li v-for="item in detail.reviews" :key="item.id">{{ item.decision === 'APPROVED' ? '审核通过' : '退回修改' }}{{ item.reason ? ` · ${item.reason}` : '' }}</li></ul>
      <ul v-if="detail.feedback.length" class="source-list"><li v-for="item in detail.feedback" :key="item.id">{{ feedbackKinds[item.kind] }} · {{ item.actor }} · {{ shanghaiTime(item.createdAt) }} · {{ item.detail }}<span v-if="item.sourceEncounterId"> · 接诊 #{{ item.sourceEncounterId }}</span></li></ul>
      <section v-if="detail.kind === 'REVISIT'" class="panel" aria-label="复诊预约记录">
        <div v-if="selectedReply" :id="'followup-request-' + selectedReply.id" tabindex="-1" class="revisit-reply">
          <h4>复诊协助回复</h4>
          <p v-if="selectedReply.outcome">{{ requestOutcomes[selectedReply.outcome] }}</p>
          <p class="preserve-lines">{{ selectedReply.publicReply }}</p>
          <p v-if="selectedReply.nextUpdateAt">下次更新 · {{ shanghaiTime(selectedReply.nextUpdateAt) }} · 跟进人 {{ detail.ownerName }}</p>
        </div>
        <div v-if="patient && (revisitPlanning || bookingNotice || bookingError)" id="revisit-booking" tabindex="-1" class="revisit-booking" aria-label="复诊号源" role="region">
          <p v-if="bookingNotice" role="status">{{ bookingNotice }}</p>
          <p v-if="bookingError" role="alert" class="error">{{ bookingError }}</p>
          <p v-if="changeVisit?.needsReschedule && !detail.needsReview">已有预约与更正后的复诊安排不符。可在这里选择符合新版的时段。</p>
          <template v-if="changeVisit?.status === 'BOOKED'">
            <p v-if="!bookingError">原预约保留到改约成功。</p>
            <p>退改约截止（北京时间） · {{ shanghaiTime(changeVisit.bookingChangesCloseAt) }} 前</p>
          </template>
          <RevisitSlotFilters v-if="revisitPlanning" :key="detail.id" @apply="applyRevisitFilters" />
          <form v-if="revisitPlanning && slots.length" class="field-grid"
            @submit.prevent="changeVisit ? confirmReschedule(detail.id, changeVisit.appointmentId) : bookRevisit(detail.id)">
            <label>{{ changeVisit ? '新复诊时段' : '复诊时段' }}
              <select v-model.number="revisitSlot" required>
                <option v-for="item in slots" :key="item.id" :value="item.id">{{ shanghaiTime(item.startsAt) }} · {{ item.department }} · {{ item.doctorName }}</option>
              </select>
            </label>
            <button :disabled="busy">{{ changeVisit ? '确认改约' : '预约复诊' }}</button>
          </form>
          <p v-else-if="revisitPlanning && availability">{{ noRevisitSlots[availability] }}</p>
          <button v-if="revisitPlanning && nextSlot" class="secondary" type="button"
            @click="loadRevisitSlots(nextSlot).catch(failure => bookingError = failure.message)">载入更多复诊号源</button>
          <div v-if="revisitPlanning && !slots.length && (availability || bookingError) || slotWatch?.status === 'WAITING'" class="row-actions">
            <button v-if="revisitPlanning && !slots.length" class="secondary" type="button"
              :disabled="busy" @click="bookingError = ''; showTask(detail.id)">刷新复诊号源</button>
            <span v-if="slotWatch?.status === 'WAITING'">候补提醒已订阅，出现合规号源后会收到站内消息。</span>
            <button v-if="slotWatch?.status === 'WAITING' || canAsk" class="secondary" type="button" :disabled="busy" @click="toggleSlotWatch(detail.id)">
              {{ slotWatch?.status === 'WAITING' ? '取消候补提醒' : '候补并提醒我' }}
            </button>
          </div>
        </div>
        <h4>复诊预约记录</h4>
        <p v-if="!visits.length">暂无关联预约。</p>
        <ul class="record-list">
          <li v-for="item in visits" :key="item.appointmentId" class="record" :data-revisit-appointment="item.appointmentId">
            <div><strong>{{ appointmentStates[item.status] }} · {{ shanghaiTime(item.startsAt) }}</strong><div>{{ item.department }} · {{ item.doctor }}</div><div v-if="item.completedAt">接诊完成 · {{ shanghaiTime(item.completedAt) }}</div></div>
          </li>
        </ul>
        <RouterLink v-if="patient && changeVisit" class="action-link"
          :to="{ path: '/workspace', query: { appointment: changeVisit.appointmentId } }">查看或取消原预约</RouterLink>
      </section>
      <FollowupRequests :key="detail.id" :task="detail" :patient="patient" :active="active"
        :can-manage="canManage" :can-ask="canAsk" :appointment-id="changeVisit?.appointmentId" :shown-request-id="selectedReply?.status === 'RESOLVED' ? selectedReply.id : undefined" @changed="requestChanged" />
      <div class="row-actions">
        <button v-if="canEdit" type="button" class="secondary" @click="edit(detail)">编辑草稿</button>
        <button v-if="canEdit && detail.status === 'DRAFT'" type="button" :disabled="busy" @click="apply('SUBMIT')">提交审核</button>
        <button v-if="canReview && detail.status === 'IN_REVIEW'" type="button" :disabled="busy" @click="apply('APPROVE')">审核通过</button>
        <button v-if="canReview && detail.status === 'IN_REVIEW'" type="button" class="secondary" @click="action.name = 'RETURN'">退回修改</button>
        <button v-if="canReview && detail.status === 'APPROVED'" type="button" :disabled="busy" @click="apply('DISPATCH')">派发随访</button>
        <button v-if="canManage && active" type="button" @click="action.name = 'VERIFY'">核实结果</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'CONTACT'">记录人工联系</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'TRANSFER'">转派</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'CLOSE'">人工结案</button>
      </div>
      <form v-if="action.name" class="field-grid" @submit.prevent="apply(action.name)">
        <label v-if="action.name === 'VERIFY' && visits.some((item) => item.completedAt)">核实依据<select v-model.number="action.encounterId"><option :value="0">人工核实记录</option><option v-for="item in visits.filter((row) => row.completedAt)" :key="item.appointmentId" :value="item.encounterId">接诊 #{{ item.encounterId }} · {{ shanghaiTime(item.completedAt!) }}</option></select></label>
        <label v-if="action.name === 'TRANSFER'">接手人<select v-model.number="action.newOwnerId" required><option :value="0">选择接手人</option><option v-for="item in directory.owners.filter((item) => item.id !== actor.id)" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label v-if="action.name === 'CLOSE'">结案原因<select v-model="action.closureReason"><option value="UNREACHABLE">无法联系</option><option value="REFUSED">患者拒绝</option><option value="WRONG_TASK">任务录错</option></select></label>
        <label>处理依据<textarea v-model.trim="action.note" maxlength="2000" required /></label>
        <button :disabled="busy">确认提交</button>
      </form>
    </article>
  </section>
</template>
