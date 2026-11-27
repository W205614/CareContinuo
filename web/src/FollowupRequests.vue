<script setup lang="ts">
import { computed, ref } from 'vue'
import { mutate } from './api'
import { requestCategories, requestOutcomes, requestStates, type Followup, type FollowupRequest } from './followup'
import { fromShanghaiInput, shanghaiTime } from './time'
import DepartmentTemplates from './DepartmentTemplates.vue'
import RevisitAssistanceForm from './RevisitAssistanceForm.vue'

const props = defineProps<{ task: Followup; patient: boolean; active: boolean; canManage: boolean; canAsk: boolean; demo: boolean; appointmentId?: number; shownRequestId?: number }>()
const emit = defineEmits<{ changed: [] }>()
const feedback = ref(false)
const category = ref('QUESTION')
const note = ref('')
const nextUpdate = ref('')
const replying = ref<number>()
const outcome = ref('SLOTS_AVAILABLE')
const reply = ref('')
const editingResult = ref(false)
const resultStage = ref('CHECKING')
const resultMessage = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const assistance = computed(() => props.task.requests.find(item => item.category === 'REVISIT_ASSISTANCE' && item.status !== 'RESOLVED'))
const history = computed(() => props.task.requests.filter(item => item.id !== props.shownRequestId))
const result = computed(() => props.task.resultUpdates[0])
const resultLabels: Record<string, string> = { CHECKING: '医护核对中', PLAN_AVAILABLE: '已有后续安排' }
const replyNeedsUpdate = computed(() => ['NO_SLOTS', 'REVIEW_NEEDED'].includes(outcome.value))
const assistanceOutcomes = ['SLOTS_AVAILABLE', 'NO_SLOTS', 'REVIEW_NEEDED']
const replyTemplates: Record<string, string> = {
  SLOTS_AVAILABLE: '目前已有符合安排的号源，请在原随访查看并自行确认。',
  NO_SLOTS: '目前暂无符合安排的号源，随访负责人会继续协调。',
  REVIEW_NEEDED: '复诊安排需要重新核对，请关注本随访的更新。',
  REPLIED: '已查看您的反馈，后续处理信息可在本随访查看。',
}

async function write(path: string, body: object, message: string) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await mutate(`/followups/${props.task.id}${path}`, { ...body, version: props.task.version })
    notice.value = message
    emit('changed')
    return true
  } catch (failure) {
    error.value = (failure as Error).message
    return false
  } finally { busy.value = false }
}

async function sendFeedback() {
  if (await write('/requests', { category: category.value, note: note.value }, '反馈已提交。')) {
    feedback.value = false
    note.value = ''
  }
}

function assistanceSubmitted() {
  notice.value = '复诊协助已提交。'
  emit('changed')
}

function openReply(item: FollowupRequest) {
  replying.value = item.id
  outcome.value = item.category === 'REVISIT_ASSISTANCE' ? 'SLOTS_AVAILABLE' : 'REPLIED'
  reply.value = ''
  nextUpdate.value = ''
}

async function respond(item: FollowupRequest) {
  if (await write(`/requests/${item.id}/actions`, { action: 'REPLY', outcome: outcome.value, reply: reply.value,
    nextUpdateAt: replyNeedsUpdate.value && nextUpdate.value ? fromShanghaiInput(nextUpdate.value) : null }, '回复已发布给患者。')) {
    replying.value = undefined
  }
}

async function publishResult() {
  if (await write('/result-updates', { stage: resultStage.value, message: resultMessage.value }, '检查结果进度已更新。')) {
    editingResult.value = false
    resultMessage.value = ''
  }
}
</script>

<template>
  <section class="panel" aria-label="协助与反馈">
    <h4>协助与反馈</h4>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="task.kind === 'PENDING_RESULT' && (!patient || task.status !== 'CLOSED')">
      <p id="followup-result-progress" tabindex="-1"><strong>检查结果进度 · {{ task.needsReview ? '等待医护确认新版' : result ? resultLabels[result.stage] : '等待结果' }}</strong></p>
      <p v-if="result?.patientMessage" class="preserve-lines">{{ result.patientMessage }}</p>
      <button v-if="active && canManage && !editingResult" class="secondary" type="button" @click="editingResult = true">更新检查结果进度</button>
      <form v-if="active && canManage && editingResult" class="field-grid" @submit.prevent="publishResult">
        <label>检查结果进度<select v-model="resultStage"><option v-for="(label, key) in resultLabels" :key="key" :value="key">{{ label }}</option></select></label>
        <label v-if="demo">患者可见说明<textarea v-model.trim="resultMessage" maxlength="2000" :required="resultStage === 'PLAN_AVAILABLE'" /></label>
        <button :disabled="busy">发布进度</button>
      </form>
    </template>
    <p v-if="task.needsReview && assistance">复诊协助已保留，等待新版安排确认后继续处理。</p>
    <RevisitAssistanceForm v-if="patient && active && canAsk && !assistance" :task-id="task.id" :version="task.version"
      :appointment-id="appointmentId" :disabled="busy" @submitted="assistanceSubmitted" />
    <div v-if="patient && active" class="row-actions">
      <button v-if="!feedback" class="secondary" type="button" @click="feedback = true">填写反馈或联系医护</button>
    </div>
    <form v-if="patient && active && feedback" class="field-grid" @submit.prevent="sendFeedback">
      <label>反馈类型<select v-model="category">
        <option value="COMPLETED">已完成</option><option value="DIFFICULTY">遇到困难</option><option value="QUESTION">有疑问</option>
      </select></label>
      <label>补充说明<textarea v-model.trim="note" maxlength="2000" /></label>
      <p>留言由医护在工作日 09:00–17:00（北京时间）查看。</p>
      <button :disabled="busy">提交反馈</button>
    </form>
    <ul v-if="history.length" class="record-list">
      <li v-for="item in history" :id="'followup-request-' + item.id" :key="item.id" tabindex="-1" class="record">
        <div>
          <strong>{{ requestCategories[item.category] }} · {{ requestStates[item.status] }}</strong>
          <div>{{ shanghaiTime(item.createdAt) }}</div>
          <div v-if="item.status !== 'RESOLVED'">
            {{ item.nextUpdateAt ? '下次更新' : '首次回复目标' }} · {{ shanghaiTime(item.nextUpdateAt ?? item.responseDueAt) }}
            · 跟进人 {{ task.ownerName }}
          </div>
          <div v-if="item.patientNote" class="preserve-lines">患者说明 · {{ item.patientNote }}</div>
          <div v-if="item.outcome">{{ requestOutcomes[item.outcome] }}</div>
          <div v-if="item.publicReply" class="preserve-lines">医护回复 · {{ item.publicReply }}</div>
          <details v-if="task.replies.filter(reply => reply.requestId === item.id).length > 1">
            <summary>历史回复</summary>
            <ul class="source-list">
              <li v-for="reply in task.replies.filter(reply => reply.requestId === item.id).slice(1)" :key="reply.id">
                {{ shanghaiTime(reply.createdAt) }} · {{ requestOutcomes[reply.outcome] }} · {{ reply.publicReply }}
              </li>
            </ul>
          </details>
        </div>
        <div v-if="canManage && active && item.status !== 'RESOLVED'" class="row-actions">
          <button v-if="item.status === 'SUBMITTED'" type="button" class="secondary" :disabled="busy"
            @click="write('/requests/' + item.id + '/actions', { action: 'START' }, '已开始处理。')">开始处理</button>
          <button v-if="replying !== item.id" type="button" :disabled="busy" @click="openReply(item)">回复患者</button>
        </div>
        <form v-if="canManage && active && item.status !== 'RESOLVED' && replying === item.id" class="field-grid message-detail" @submit.prevent="respond(item)">
          <label v-if="item.category === 'REVISIT_ASSISTANCE'">处理结果<select v-model="outcome">
            <option v-for="key in assistanceOutcomes" :key="key" :value="key">{{ requestOutcomes[key] }}</option>
          </select></label>
          <button type="button" class="secondary" @click="reply = replyTemplates[outcome] ?? ''">填入回复模板</button>
          <label v-if="replyNeedsUpdate">下次更新时间（北京时间）<input v-model="nextUpdate" type="datetime-local" required /></label>
          <label>患者可见回复<textarea v-model.trim="reply" maxlength="2000" required /></label>
          <button :disabled="busy">发布回复</button>
          <DepartmentTemplates :department-id="task.ownerDepartmentId" kind="REPLY" :content="reply"
            @apply="template => reply = template.content" />
        </form>
      </li>
    </ul>
  </section>
</template>
