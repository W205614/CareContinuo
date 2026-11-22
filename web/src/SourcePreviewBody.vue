<script setup lang="ts">
import { computed } from 'vue'
import type { SourceContext } from './followup'

const props = defineProps<{ episodeId: number; context: SourceContext }>()
const sourceUrl = computed(() => `/api/v1/episodes/${props.episodeId}/versions/${props.context.target.versionId}/download`)
</script>

<template>
  <p>{{ context.target.title }} · 版本 {{ context.target.versionNumber }}</p>
  <p><strong><template v-if="context.target.pageNumber">第 {{ context.target.pageNumber }} 页 · </template>第 {{ context.target.lineNumber }} 行</strong></p>
  <div class="row-actions">
    <a v-if="context.target.pageNumber" :href="sourceUrl + '?inline=true#page=' + context.target.pageNumber"
      target="_blank" rel="noopener">打开 PDF 第 {{ context.target.pageNumber }} 页</a>
    <a :href="sourceUrl">下载该版本原件</a>
  </div>
  <ul class="source-lines" :aria-label="context.target.pageNumber ? '本页原文文本' : '引用行附近原文'">
    <li v-for="line in context.lines" :key="line.id" :data-line="line.lineNumber"
      :aria-current="line.id === context.target.id ? 'true' : undefined"
      :tabindex="line.id === context.target.id ? -1 : undefined">
      <span class="source-line-number">第 {{ line.lineNumber }} 行</span>
      <mark v-if="line.id === context.target.id" class="preserve-lines">{{ line.excerpt }}</mark>
      <span v-else class="preserve-lines">{{ line.excerpt }}</span>
    </li>
  </ul>
</template>
