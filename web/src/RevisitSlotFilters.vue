<script setup lang="ts">
import { ref } from 'vue'
import type { RevisitFilters } from './appointments'

const emit = defineEmits<{ apply: [filters: RevisitFilters] }>()
const date = ref('')
const doctor = ref('')
const period = ref('')

function clear() {
  date.value = ''
  doctor.value = ''
  period.value = ''
  emit('apply', {})
}
</script>

<template>
  <details>
    <summary>筛选复诊号源</summary>
    <form class="field-grid" @submit.prevent="emit('apply', { date, doctor, period })">
      <label>复诊日期<input v-model="date" type="date" /></label>
      <label>医生姓名<input v-model.trim="doctor" maxlength="100" type="search" /></label>
      <label>时段<select v-model="period" aria-label="筛选号源时段"><option value="">全天</option><option value="AM">上午</option><option value="PM">下午及晚间</option></select></label>
      <button type="submit">应用筛选</button>
      <button type="button" class="secondary" @click="clear">清除筛选</button>
    </form>
  </details>
</template>
