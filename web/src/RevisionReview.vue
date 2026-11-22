<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Followup, RevisionComparison, Revisit } from './followup'
import { appointmentStates } from './appointments'
import SourceCitations from './SourceCitations.vue'
import SourceComparison from './SourceComparison.vue'
import { shanghaiTime } from './time'

const props = defineProps<{ current: Followup; previous: RevisionComparison; visits: Revisit[] }>()
const showSources = ref(false)
const columns = computed(() => [
  { label: '上次派发', revision: props.previous }, { label: '当前草稿', revision: props.current },
])
const kinds: Record<string, string> = { REVISIT: '复诊', PENDING_RESULT: '待出检查结果' }
const fields = computed(() => [
  { label: '事项标题', old: props.previous.title, current: props.current.title },
  { label: '随访类型', old: kinds[props.previous.kind], current: kinds[props.current.kind], showValues: true },
  { label: '复诊科室', old: props.previous.revisitDepartment, current: props.current.revisitDepartment, showValues: true },
  { label: '复诊日期', old: dates(props.previous), current: dates(props.current), showValues: true },
  { label: '完成期限', old: shanghaiTime(props.previous.dueAt), current: shanghaiTime(props.current.dueAt), showValues: true },
  { label: '患者须知', old: props.previous.instructions, current: props.current.instructions },
  { label: '手工依据', old: props.previous.manualReference, current: props.current.manualReference },
].filter(field => field.old != null || field.current != null))
const changes = computed(() => {
  const entries = fields.value.filter(field => field.old !== field.current)
  const old = props.previous.sources
  const current = props.current.sources
  if (old.length !== current.length || old.some((source, index) => source.id !== current[index]?.id)) {
    entries.push({ label: '原文引用', old: null, current: null })
  }
  return entries
})
const affected = computed(() => props.visits.filter(visit => visit.needsReschedule))

function sentences(value: string | null | undefined, other: string | null | undefined) {
  const split = (text: string) => text.match(/[^。！？!?\n]+[。！？!?]?|\n/g) ?? []
  const original = new Set(split(other ?? ''))
  return split(value ?? '').map(text => ({ text, changed: !original.has(text) && text.trim() !== '' }))
}

function dates(revision: Followup | RevisionComparison) {
  if (!revision.revisitFrom) return null
  return revision.revisitFrom === revision.revisitTo ? revision.revisitFrom : revision.revisitFrom + ' 至 ' + revision.revisitTo
}
</script>

<template>
  <section class="panel" aria-label="审核版本对比">
    <h4>审核版本对比</h4>
    <section class="revision-summary" aria-label="变更摘要">
      <div>
        <h5>变更项</h5>
        <ul v-if="changes.length" class="review-change-list">
          <li v-for="change in changes" :key="change.label">
            <strong>{{ change.label }}</strong>
            <span v-if="change.showValues">{{ change.old ?? '未填写' }} → {{ change.current ?? '未填写' }}</span>
          </li>
        </ul>
        <p v-else>无变更项</p>
      </div>
      <div>
        <h5>与新安排不符的预约<span v-if="affected.length">（{{ affected.length }}）</span></h5>
        <ul v-if="affected.length" class="affected-visits">
          <li v-for="visit in affected" :key="visit.appointmentId">
            {{ visit.department }} · {{ shanghaiTime(visit.startsAt) }} · {{ appointmentStates[visit.status] }}
          </li>
        </ul>
        <p v-else>无受影响预约</p>
      </div>
    </section>
    <button v-if="previous.sources.length && current.sources.length" type="button" class="secondary"
      @click="showSources = !showSources">{{ showSources ? '收起原文对照' : '并排核对新旧原文' }}</button>
    <SourceComparison v-if="showSources" :episode-id="current.episodeId" :previous="previous.sources" :current="current.sources" />
    <div class="revision-grid">
      <div v-for="({ label, revision }, index) in columns" :key="label">
        <h5>{{ label }} · 版本 {{ revision.version }}</h5>
        <dl>
          <template v-for="field in fields" :key="field.label">
            <dt>{{ field.label }}<strong v-if="index === 1 && field.old !== field.current"> · 已变更</strong></dt>
            <dd v-if="field.label === '患者须知' && field.old && field.current && field.old !== field.current" class="preserve-lines"><template
                v-for="(part, position) in sentences(index === 0 ? field.old : field.current, index === 0 ? field.current : field.old)"
                :key="position"><mark v-if="part.changed">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></dd>
            <dd v-else class="preserve-lines">{{ (index === 0 ? field.old : field.current) ?? '未填写' }}</dd>
          </template>
        </dl>
        <SourceCitations :episode-id="current.episodeId" :sources="revision.sources" />
      </div>
    </div>
  </section>
</template>
