<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, mutate } from './api'
import type { DepartmentTemplate } from './followup'

const props = defineProps<{ departmentId: number; kind: string; content: string; initialName?: string; allowDrafts?: boolean }>()
const emit = defineEmits<{ apply: [template: DepartmentTemplate] }>()
const templates = ref<DepartmentTemplate[]>([])
const name = ref('')
const open = ref(false)
const enableSave = ref(false)
const saving = ref(false)
const error = ref('')
const visible = computed(() => templates.value.filter(item => props.allowDrafts ? item.kind !== 'REPLY' : item.kind === props.kind))

async function load() {
  error.value = ''
  try {
    templates.value = await api<DepartmentTemplate[]>('/followups/templates?departmentId=' + props.departmentId)
  } catch (failure) { error.value = (failure as Error).message }
}

function toggle(event: Event) {
  open.value = (event.currentTarget as HTMLDetailsElement).open
  if (open.value) void load()
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await mutate('/followups/templates', { departmentId: props.departmentId, kind: props.kind, name: name.value, content: props.content })
    enableSave.value = false
    await load()
  } catch (failure) { error.value = (failure as Error).message }
  finally { saving.value = false }
}

watch(() => props.departmentId, () => {
  templates.value = []
  enableSave.value = false
  if (open.value) void load()
})
</script>

<template>
  <details @toggle="toggle">
    <summary>科室共用模板</summary>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="!visible.length && !error">本科室暂无此类模板。</p>
    <div class="row-actions">
      <button v-for="item in visible" :key="item.id" class="secondary" type="button" @click="emit('apply', item)">
        {{ item.name }}
      </button>
      <button v-if="!enableSave" type="button" class="secondary" :disabled="!content.trim()"
        @click="name = initialName ?? ''; enableSave = true">另存为科室模板</button>
    </div>
    <div v-if="enableSave" class="field-grid">
      <label>共用模板名称<input v-model.trim="name" maxlength="140" /></label>
      <p>模板供本科室复用，请移除患者个人信息。</p>
      <button type="button" :disabled="saving || !name || !content.trim()" @click="save">保存共用模板</button>
    </div>
  </details>
</template>
