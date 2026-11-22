<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, mutate } from './api'
import SourceLocator from './SourceLocator.vue'

interface Reference { segmentId: number; quote: string }
export interface Candidate { kind: string; title: string; instructions: string; dueDate: string | null; references: Reference[] }
interface Job { id: number; status: string; failureCode: string | null; outcome: { model: string; candidates: Candidate[]; conflicts: { reason: string; references: Reference[] }[] } | null }

const props = defineProps<{ episodeId: number; sourceVersion: number; reviewOnly?: boolean }>()
const emit = defineEmits<{ use: [candidate: Candidate] }>()
const jobs = ref<Job[]>([])
const busy = ref(false)
const error = ref('')
const reasons = ref<Record<number, string>>({})
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let requestVersion = 0

const statusNames: Record<string, string> = { QUEUED: '已排队', RUNNING: '正在提取', READY: '可查看候选', CONFLICT: '资料冲突', RESOLVED: '误报已解除', FAILED: '生成失败', STALE: '资料已更新' }
const failures: Record<string, string> = {
  MODEL_KEY_UNAVAILABLE: '本机 Agent 尚未安装密钥。', MODEL_AUTHENTICATION_FAILED: '模型凭据未通过认证。',
  MODEL_PERMISSION_DENIED: '模型调用权限不足。', MODEL_UNAVAILABLE: '当前项目无法访问配置的模型。',
  MODEL_TIMEOUT: '模型响应超时。', MODEL_SOURCE_MISMATCH: '候选内容无法定位到原文。',
  MODEL_NO_USABLE_OUTPUT: '模型没有返回可用事项。', MODEL_QUOTA_OR_RATE_LIMIT: '模型请求达到配额或速率限制。',
  MODEL_INTERRUPTED: '模型生成中断。', SOURCE_TOO_LARGE: '本次授权资料文本过长。',
}

async function refresh() {
  const episodeId = props.episodeId
  const generation = ++requestVersion
  clearTimeout(refreshTimer)
  try {
    const snapshot = await api<Job[]>(`/episodes/${episodeId}/agent-jobs`)
    if (generation !== requestVersion) return
    jobs.value = props.reviewOnly ? snapshot.filter((item) => ['CONFLICT', 'RESOLVED'].includes(item.status)) : snapshot
    if (snapshot.some((item) => ['QUEUED', 'RUNNING'].includes(item.status))) refreshTimer = setTimeout(refresh, 1500)
  } catch (failure) {
    if (generation === requestVersion) error.value = (failure as Error).message
  }
}

async function generate() {
  busy.value = true
  error.value = ''
  try { await mutate(`/episodes/${props.episodeId}/agent-jobs`); await refresh() }
  catch (failure) { error.value = (failure as Error).message }
  finally { busy.value = false }
}

async function resolve(jobId: number) {
  busy.value = true
  error.value = ''
  try {
    await mutate('/agent-jobs/' + jobId + '/resolve-conflict', { reason: reasons.value[jobId] })
    delete reasons.value[jobId]
    await refresh()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

watch(() => [props.episodeId, props.sourceVersion], () => {
  jobs.value = []
  reasons.value = {}
  error.value = ''
  void refresh()
}, { immediate: true })
onBeforeUnmount(() => { requestVersion++; clearTimeout(refreshTimer) })
</script>

<template>
  <div v-if="!reviewOnly || jobs.length || error" class="panel">
    <div class="section-heading">
      <h3>{{ reviewOnly ? '资料冲突核实' : 'GPT 草稿候选' }}</h3>
      <button v-if="!reviewOnly" type="button" class="secondary" :disabled="busy || jobs.some((item) => ['QUEUED', 'RUNNING'].includes(item.status))" @click="generate">从当前资料提取</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <details v-for="job in jobs" :key="job.id" :open="job.id === jobs[0]?.id">
      <summary>{{ statusNames[job.status] }}<span v-if="job.outcome"> · {{ job.outcome.model }}</span></summary>
      <p v-if="job.status === 'FAILED'">{{ failures[job.failureCode ?? ''] ?? '本次未能完成提取。' }} 可以继续手工填写草稿。</p>
      <p v-if="job.status === 'STALE'">请从当前资料重新提取。</p>
      <ul v-if="job.outcome?.conflicts.length" class="source-list">
        <li v-for="conflict in job.outcome.conflicts" :key="conflict.reason">
          <strong>{{ conflict.reason }}</strong>
          <div v-for="evidence in conflict.references" :key="evidence.segmentId">
            <SourceLocator :episode-id="episodeId" :segment-id="evidence.segmentId">定位原文 · {{ evidence.quote }}</SourceLocator>
          </div>
        </li>
      </ul>
      <template v-if="job.status === 'CONFLICT'">
        <p>请核实引用的原文。资料有误时由随访人员更正，模型误报由审核医生解除。</p>
        <form v-if="reviewOnly" class="field-grid" @submit.prevent="resolve(job.id)">
          <label>误报处理依据<textarea v-model.trim="reasons[job.id]" maxlength="2000" required /></label>
          <button type="submit" :disabled="busy">解除误报</button>
        </form>
      </template>
      <ul v-if="job.status === 'READY' && job.outcome" class="record-list">
        <li v-for="candidate in job.outcome.candidates" :key="`${candidate.title}-${candidate.references[0]?.segmentId}`" class="record">
          <div>
            <strong>{{ candidate.title }}</strong><p>{{ candidate.instructions }}</p>
            <div v-for="reference in candidate.references" :key="reference.segmentId">
              <SourceLocator :episode-id="episodeId" :segment-id="reference.segmentId">定位原文 · {{ reference.quote }}</SourceLocator>
            </div>
          </div>
          <button type="button" class="secondary" @click="emit('use', candidate)">填入草稿</button>
        </li>
      </ul>
    </details>
  </div>
</template>
