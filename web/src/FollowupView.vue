<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, mutate, type Actor } from './api'
import { feedbackKinds, followupStates, type CareEpisode, type Directory, type DocumentVersion, type Followup, type Source } from './followup'
import { fromShanghaiInput, shanghaiTime } from './time'

const props = defineProps<{ actor: Actor }>()
const patient = props.actor.roles.includes('PATIENT')
const followup = props.actor.roles.includes('FOLLOWUP')
const reviewer = props.actor.roles.includes('REVIEWER')
const episodes = ref<CareEpisode[]>([])
const tasks = ref<Followup[]>([])
const directory = ref<Directory>({ patients: [], reviewers: [], owners: [] })
const episodeId = ref(0)
const documents = ref<DocumentVersion[]>([])
const sources = ref<Source[]>([])
const detail = ref<Followup>()
const editing = ref<number>()
const error = ref('')
const notice = ref('')
const busy = ref(false)
const discharge = ref({ patientId: 0, reference: '', careDate: '' })
const doc = ref({ documentId: 0, title: '', text: '' })
const selectedFile = ref<File>()
const fileKey = ref(0)
const draft = ref({ reviewerId: 0, kind: 'REVISIT', title: '', instructions: '', dueAt: '', segmentIds: [] as number[], manualReference: '' })
const action = ref({ name: '', note: '', newOwnerId: 0, closureReason: 'UNREACHABLE' })
const currentDocuments = computed(() => documents.value.filter((row, index, rows) => rows.findIndex((item) => item.id === row.id) === index))
const active = computed(() => !!detail.value && ['DISPATCHED', 'PATIENT_REPLIED'].includes(detail.value.status))
const canEdit = computed(() => !!detail.value && ['DRAFT', 'APPROVED'].includes(detail.value.status) && detail.value.ownerId === props.actor.id)
const canManage = computed(() => !!detail.value && !['VERIFIED', 'CLOSED'].includes(detail.value.status) && detail.value.ownerId === props.actor.id)
const canReview = computed(() => !!detail.value && reviewer && detail.value.reviewerId === props.actor.id)

async function refresh() {
  tasks.value = await api<Followup[]>('/followups')
  if (!patient) {
    const [rows, choices] = await Promise.all([api<CareEpisode[]>('/episodes'), api<Directory>('/care-directory')])
    episodes.value = rows
    directory.value = choices
    draft.value.reviewerId ||= choices.reviewers[0]?.id ?? 0
    discharge.value.patientId ||= choices.patients[0]?.id ?? 0
  }
}

async function run(write: () => Promise<unknown>, message: string) {
  error.value = ''
  notice.value = ''
  busy.value = true
  try { await write(); notice.value = message; await refresh() }
  catch (failure) { error.value = (failure as Error).message }
  finally { busy.value = false }
}

async function chooseEpisode(id: number) {
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

async function showTask(id: number) {
  error.value = ''
  action.value.name = ''
  try { detail.value = await api<Followup>(`/followups/${id}`) }
  catch (failure) { error.value = (failure as Error).message }
}

async function edit(item: Followup) {
  await chooseEpisode(item.episodeId)
  editing.value = item.id
  const shanghaiDate = new Date(new Date(item.dueAt).getTime() + 8 * 3_600_000)
  draft.value = { reviewerId: item.reviewerId, kind: item.kind, title: item.title,
    instructions: item.instructions ?? '', dueAt: shanghaiDate.toISOString().slice(0, 16),
    segmentIds: item.sources.map((source) => source.id).filter((id) => sources.value.some((source) => source.id === id)),
    manualReference: item.manualReference ?? '' }
}

function saveDraft() {
  return run(async () => {
    const data = { kind: draft.value.kind, title: draft.value.title, instructions: draft.value.instructions,
      dueAt: fromShanghaiInput(draft.value.dueAt), segmentIds: draft.value.segmentIds,
      manualReference: draft.value.manualReference || null }
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
  }, '草稿已保存。')
}

function apply(name: string) {
  const item = detail.value
  if (!item) return
  return run(async () => {
    await mutate(`/followups/${item.id}/actions`, { action: name, version: item.version, note: action.value.note || null,
      newOwnerId: action.value.newOwnerId || null, closureReason: name === 'CLOSE' ? action.value.closureReason : null })
    action.value.note = ''
    if (name === 'TRANSFER') detail.value = undefined
    else await showTask(item.id)
  }, '随访已更新。')
}

onMounted(async () => {
  try { await refresh() } catch (failure) { error.value = (failure as Error).message }
})
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
        <form class="panel draft-form" @submit.prevent="saveDraft">
          <h3>{{ editing ? `更正随访草稿 ${editing}` : '新建随访草稿' }}</h3>
          <div class="field-grid">
            <label v-if="!editing">审核医生<select v-model.number="draft.reviewerId" required><option v-for="item in directory.reviewers" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
            <label>随访类型<select v-model="draft.kind"><option value="REVISIT">复诊</option><option value="PENDING_RESULT">待出检查结果</option></select></label>
            <label>事项标题<input v-model.trim="draft.title" maxlength="140" required /></label>
            <label>完成期限（北京时间）<input v-model="draft.dueAt" type="datetime-local" required /></label>
            <label>安排内容<textarea v-model.trim="draft.instructions" maxlength="4000" required /></label>
            <label>手工记录依据<textarea v-model.trim="draft.manualReference" maxlength="600" /></label>
          </div>
          <details v-if="sources.length"><summary>选择资料出处（已选 {{ draft.segmentIds.length }}）</summary><ul class="source-list"><li v-for="item in sources" :key="item.id"><label><input v-model="draft.segmentIds" :value="item.id" type="checkbox" /> {{ item.title }} · 版本 {{ item.versionNumber }}<span v-if="item.pageNumber"> · 第 {{ item.pageNumber }} 页</span> · 第 {{ item.lineNumber }} 行 · {{ item.excerpt }}</label></li></ul></details>
          <button :disabled="busy">保存随访草稿</button>
        </form>
      </template>
    </div>
    <p v-if="!tasks.length">暂无随访任务。</p>
    <ul class="record-list">
      <li v-for="item in tasks" :key="item.id" class="record" :data-followup-id="item.id">
        <div><strong>{{ item.title }} · {{ followupStates[item.status] }}</strong><div>{{ patient ? '完成期限' : item.patient }} · {{ shanghaiTime(item.dueAt) }}</div></div>
        <button type="button" class="secondary" @click="showTask(item.id)">查看随访</button>
      </li>
    </ul>
    <article v-if="detail" class="panel followup-detail">
      <h3>{{ detail.title }}</h3>
      <p>{{ followupStates[detail.status] }} · 完成期限 {{ shanghaiTime(detail.dueAt) }}</p>
      <p v-if="detail.needsReview">资料已更正，等待医护确认。</p>
      <p v-if="detail.instructions" class="preserve-lines">{{ detail.instructions }}</p>
      <p v-if="detail.manualReference">手工依据 · {{ detail.manualReference }}</p>
      <ul v-if="detail.sources.length" class="source-list"><li v-for="item in detail.sources" :key="item.id"><a :href="`/api/v1/episodes/${detail.episodeId}/versions/${item.versionId}/download`">{{ item.title }} · 版本 {{ item.versionNumber }}</a><span v-if="item.pageNumber"> · 第 {{ item.pageNumber }} 页</span> · 第 {{ item.lineNumber }} 行 · {{ item.excerpt }}</li></ul>
      <ul v-if="detail.reviews.length" class="source-list"><li v-for="item in detail.reviews" :key="item.id">{{ item.decision === 'APPROVED' ? '审核通过' : '退回修改' }}{{ item.reason ? ` · ${item.reason}` : '' }}</li></ul>
      <ul v-if="detail.feedback.length" class="source-list"><li v-for="item in detail.feedback" :key="item.id">{{ feedbackKinds[item.kind] }} · {{ item.actor }} · {{ shanghaiTime(item.createdAt) }} · {{ item.detail }}</li></ul>
      <div class="row-actions">
        <button v-if="canEdit" type="button" class="secondary" @click="edit(detail)">编辑草稿</button>
        <button v-if="canEdit && detail.status === 'DRAFT'" type="button" :disabled="busy" @click="apply('SUBMIT')">提交审核</button>
        <button v-if="canReview && detail.status === 'IN_REVIEW'" type="button" :disabled="busy" @click="apply('APPROVE')">审核通过</button>
        <button v-if="canReview && detail.status === 'IN_REVIEW'" type="button" class="secondary" @click="action.name = 'RETURN'">退回修改</button>
        <button v-if="canReview && detail.status === 'APPROVED'" type="button" :disabled="busy" @click="apply('DISPATCH')">派发随访</button>
        <button v-if="patient && active" type="button" @click="action.name = 'FEEDBACK'">填写反馈或联系医护</button>
        <button v-if="canManage && active" type="button" @click="action.name = 'VERIFY'">核实结果</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'CONTACT'">记录人工联系</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'TRANSFER'">转派</button>
        <button v-if="canManage" type="button" class="secondary" @click="action.name = 'CLOSE'">人工结案</button>
      </div>
      <form v-if="action.name" class="field-grid" @submit.prevent="apply(action.name)">
        <label v-if="action.name === 'TRANSFER'">接手人<select v-model.number="action.newOwnerId" required><option :value="0">选择接手人</option><option v-for="item in directory.owners.filter((item) => item.id !== actor.id)" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label v-if="action.name === 'CLOSE'">结案原因<select v-model="action.closureReason"><option value="UNREACHABLE">无法联系</option><option value="REFUSED">患者拒绝</option><option value="WRONG_TASK">任务录错</option></select></label>
        <label>{{ action.name === 'FEEDBACK' ? '反馈内容' : '处理依据' }}<textarea v-model.trim="action.note" maxlength="2000" required /></label>
        <button :disabled="busy">确认提交</button>
      </form>
    </article>
  </section>
</template>
