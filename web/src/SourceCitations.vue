<script setup lang="ts">
import type { Source } from './followup'
import SourceLocator from './SourceLocator.vue'

defineProps<{ episodeId: number; sources: Source[] }>()
</script>

<template>
  <ul v-if="sources.length" class="source-list">
    <li v-for="source in sources" :key="source.id">
      <SourceLocator :episode-id="episodeId" :segment-id="source.id">
        定位原文 · {{ source.title }} · 版本 {{ source.versionNumber }}<span v-if="source.pageNumber"> · 第 {{ source.pageNumber }} 页</span> · 第 {{ source.lineNumber }} 行
      </SourceLocator>
      <p class="preserve-lines">{{ source.excerpt }}</p>
    </li>
  </ul>
</template>
