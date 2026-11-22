<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from './api'
import type { Source, SourceContext } from './followup'
import SourcePreviewBody from './SourcePreviewBody.vue'

const props = defineProps<{ episodeId: number; previous: Source[]; current: Source[] }>()
const oldId = ref(0)
const newId = ref(0)
const old = ref<SourceContext>()
const currentPreview = ref<SourceContext>()
const error = ref('')
let revision = 0

watch(() => [props.previous, props.current], () => {
  oldId.value = props.previous[0]?.id ?? 0
  newId.value = props.current[0]?.id ?? 0
}, { immediate: true })

watch([oldId, newId], async ([left, right]) => {
  const load = ++revision
  old.value = undefined
  currentPreview.value = undefined
  error.value = ''
  if (!left || !right) return
  try {
    const [before, after] = await Promise.all([left, right].map(id =>
      api<SourceContext>(`/episodes/${props.episodeId}/segments/${id}/context`)))
    if (load !== revision) return
    old.value = before
    currentPreview.value = after
  } catch (failure) {
    if (load === revision) error.value = (failure as Error).message
  }
}, { immediate: true })
</script>

<template>
  <section class="panel" aria-label="并排原文">
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div class="revision-grid">
      <div>
        <label>上次派发引用 <select v-model.number="oldId">
          <option v-for="source in previous" :key="source.id" :value="source.id">{{ source.title }} · {{ source.pageNumber ? '第 ' + source.pageNumber + ' 页 · ' : '' }}第 {{ source.lineNumber }} 行</option>
        </select></label>
        <SourcePreviewBody v-if="old" :episode-id="episodeId" :context="old" />
      </div>
      <div>
        <label>当前草稿引用 <select v-model.number="newId">
          <option v-for="source in current" :key="source.id" :value="source.id">{{ source.title }} · {{ source.pageNumber ? '第 ' + source.pageNumber + ' 页 · ' : '' }}第 {{ source.lineNumber }} 行</option>
        </select></label>
        <SourcePreviewBody v-if="currentPreview" :episode-id="episodeId" :context="currentPreview" />
      </div>
    </div>
  </section>
</template>
