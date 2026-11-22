<script setup lang="ts">
import { ref } from 'vue'
import { mutate } from './api'

const props = defineProps<{ taskId: number; version: number; appointmentId?: number; disabled?: boolean }>()
const emit = defineEmits<{ submitted: [id: number] }>()
const note = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const request = await mutate<{ id: number }>(`/followups/${props.taskId}/requests`, {
      category: 'REVISIT_ASSISTANCE', version: props.version, appointmentId: props.appointmentId, note: note.value,
    })
    note.value = ''
    emit('submitted', request.id)
  } catch (failure) { error.value = (failure as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <form class="field-grid" @submit.prevent="submit">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <label>可配合时段或补充说明（选填）<textarea v-model.trim="note" maxlength="2000" /></label>
    <button :disabled="disabled || busy">申请复诊协助</button>
  </form>
</template>
