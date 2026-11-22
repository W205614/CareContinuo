<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { api } from './api'
import type { SourceContext } from './followup'
import SourcePreviewBody from './SourcePreviewBody.vue'

const props = defineProps<{ episodeId: number; segmentId: number }>()
const dialog = ref<HTMLDialogElement>()
const context = ref<SourceContext>()
const error = ref('')
async function openSource() {
  context.value = undefined
  error.value = ''
  dialog.value?.showModal()
  try {
    context.value = await api<SourceContext>(`/episodes/${props.episodeId}/segments/${props.segmentId}/context`)
    await nextTick()
    if (!dialog.value?.open) return
    const line = dialog.value.querySelector<HTMLElement>('[aria-current="true"]')
    line?.focus({ preventScroll: true })
    line?.scrollIntoView({ block: 'nearest' })
  } catch (failure) {
    error.value = (failure as Error).message
  }
}
</script>

<template>
  <button type="button" class="source-jump" @click="openSource"><slot /></button>
  <dialog ref="dialog" class="source-preview" aria-label="原文定位">
    <div class="section-heading">
      <h3>原文定位</h3>
      <button class="secondary" type="button" @click="dialog?.close()">关闭</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <SourcePreviewBody v-else-if="context" :episode-id="episodeId" :context="context" />
    <p v-else role="status">正在读取原文…</p>
  </dialog>
</template>
