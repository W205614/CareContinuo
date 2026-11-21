<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, mutate } from './api'

interface Department { id: number; name: string; enabled: boolean }
interface StaffInput {
  displayName: string
  enabled: boolean
  roles: string[]
  departmentIds: number[]
  doctorDepartmentId: number | null
}
interface Staff extends StaffInput {
  id: number
  username: string
  oidcIssuer: string | null
  oidcSubject: string | null
}
interface Assignment {
  id: number
  episodeId: number
  status: string
  ownerUnavailable: boolean
  reviewerUnavailable: boolean
}
const roles: Record<string, string> = { ADMIN: '管理员', OPERATIONS: '运营', DOCTOR: '接诊医生', FOLLOWUP: '随访人员', REVIEWER: '审核员' }
const departments = ref<Department[]>([])
const staff = ref<Staff[]>([])
const assignments = ref<Assignment[]>([])
const selection = ref(0)
const nextStaff = ref<number | null>(null)
const nextAssignment = ref<number | null>(null)
const departmentName = ref('')
const departmentId = ref(0)
const departmentEnabled = ref(true)
const username = ref('')
const issuer = ref('')
const subject = ref('')
const staffId = ref(0)
const form = ref<StaffInput>({ displayName: '', enabled: true, roles: [], departmentIds: [], doctorDepartmentId: null })
const editing = computed(() => staff.value.find((row) => row.id === staffId.value))
const compatibleDepartments = computed(() => departments.value.filter((row) => form.value.departmentIds.includes(row.id)))
const pending = ref<number | null>(null)
const nextOwner = ref(0)
const nextReviewer = ref(0)
const error = ref('')
const message = ref('')
const busy = ref(false)

async function perform(action: () => Promise<void>) {
  error.value = ''
  message.value = ''
  busy.value = true
  try {
    await action()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

async function loadStaff(afterId?: number) {
  const page = await api<{ staff: Staff[]; nextCursor: number | null }>('/admin/staff' + (afterId ? '?afterId=' + afterId : ''))
  staff.value = afterId ? [...staff.value, ...page.staff] : page.staff
  nextStaff.value = page.nextCursor
}

async function loadAssignments(afterId?: number) {
  if (!selection.value) return
  const query = new URLSearchParams({ departmentId: String(selection.value) })
  if (afterId) query.set('afterId', String(afterId))
  const page = await api<{ assignments: Assignment[]; nextCursor: number | null }>('/admin/assignments?' + query)
  assignments.value = afterId ? [...assignments.value, ...page.assignments] : page.assignments
  nextAssignment.value = page.nextCursor
}

async function load() {
  departments.value = await api<Department[]>('/admin/departments')
  selection.value ||= departments.value[0]?.id ?? 0
  await Promise.all([loadStaff(), loadAssignments()])
}

function selectDepartment() {
  const department = departments.value.find((row) => row.id === departmentId.value)
  departmentName.value = department?.name ?? ''
  departmentEnabled.value = department?.enabled ?? true
}

function selectStaff() {
  const row = editing.value
  form.value = {
    displayName: row?.displayName ?? '', enabled: row?.enabled ?? true,
    roles: [...(row?.roles ?? [])], departmentIds: [...(row?.departmentIds ?? [])],
    doctorDepartmentId: row?.doctorDepartmentId ?? null,
  }
}

function saveDepartment() {
  return perform(async () => {
    await mutate('/admin/departments' + (departmentId.value ? '/' + departmentId.value : ''), {
      name: departmentName.value, enabled: departmentEnabled.value,
    })
    await load()
    if (!departmentId.value) selectDepartment()
    message.value = '科室已保存。'
  })
}

function saveStaff() {
  return perform(async () => {
    const payload = { ...form.value, doctorDepartmentId: form.value.roles.includes('DOCTOR') ? form.value.doctorDepartmentId : null }
    await (staffId.value ? mutate('/admin/staff/' + staffId.value, payload) :
      mutate('/admin/staff', { username: username.value, issuer: issuer.value, subject: subject.value, staff: payload }))
    staffId.value = 0
    username.value = ''
    subject.value = ''
    selectStaff()
    await Promise.all([loadStaff(), loadAssignments()])
    message.value = '人员配置已保存。涉及权限的现有会话需重新登录。'
  })
}

function revokeSessions() {
  return perform(async () => {
    await mutate('/admin/staff/' + staffId.value + '/revoke-sessions')
    message.value = '已撤销该人员的会话。'
  })
}

function reassign(id: number) {
  return perform(async () => {
    await mutate('/admin/followups/' + id + '/reassign', { ownerId: nextOwner.value || null, reviewerId: nextReviewer.value || null })
    pending.value = null
    await loadAssignments()
    message.value = '任务已分派。'
  })
}

onMounted(() => perform(load))
</script>

<template>
  <section class="panel" aria-labelledby="staff-admin-heading">
    <h2 id="staff-admin-heading">科室与人员管理</h2>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <div class="admin-columns">
      <form class="admin-form" @submit.prevent="saveDepartment">
        <h3>科室</h3>
        <label>选择科室<select v-model.number="departmentId" @change="selectDepartment">
          <option :value="0">新增科室</option>
          <option v-for="row in departments" :key="row.id" :value="row.id">{{ row.name }}</option>
        </select></label>
        <label>科室名称<input v-model.trim="departmentName" required maxlength="100" /></label>
        <label><input v-model="departmentEnabled" type="checkbox" /> 启用科室</label>
        <button :disabled="busy">保存科室</button>
      </form>

      <form class="admin-form" @submit.prevent="saveStaff">
        <h3>工作人员</h3>
        <label>选择人员<select v-model.number="staffId" @change="selectStaff">
          <option :value="0">登记人员</option>
          <option v-for="row in staff" :key="row.id" :value="row.id">{{ row.displayName }}（{{ row.username }}）</option>
        </select></label>
        <button v-if="nextStaff" class="secondary" type="button" :disabled="busy" @click="perform(() => loadStaff(nextStaff!))">载入更多人员</button>
        <template v-if="!staffId">
          <label>人员账号<input v-model.trim="username" required maxlength="80" /></label>
          <label>OIDC 签发者<input v-model.trim="issuer" required maxlength="512" /></label>
          <label>OIDC Subject<input v-model="subject" required maxlength="255" /></label>
        </template>
        <label>显示姓名<input v-model.trim="form.displayName" required maxlength="100" /></label>
        <fieldset>
          <legend>角色</legend>
          <label v-for="(label, role) in roles" :key="role"><input v-model="form.roles" type="checkbox" :value="role" /> {{ label }}</label>
        </fieldset>
        <fieldset>
          <legend>授权科室</legend>
          <label v-for="row in departments" :key="row.id"><input v-model="form.departmentIds" type="checkbox" :value="row.id" /> {{ row.name }}</label>
        </fieldset>
        <label v-if="form.roles.includes('DOCTOR')">医生主科室<select v-model.number="form.doctorDepartmentId" required>
          <option value="" disabled>选择主科室</option>
          <option v-for="row in compatibleDepartments" :key="row.id" :value="row.id">{{ row.name }}</option>
        </select></label>
        <label><input v-model="form.enabled" type="checkbox" /> 启用人员</label>
        <button :disabled="busy">保存人员</button>
        <button v-if="staffId" class="secondary" type="button" :disabled="busy" @click="revokeSessions">撤销会话</button>
      </form>
    </div>
    <h3>待交接随访</h3>
    <label class="admin-filter">交接科室<select v-model.number="selection" @change="perform(() => loadAssignments())">
      <option v-for="row in departments" :key="row.id" :value="row.id">{{ row.name }}</option>
    </select></label>
    <p v-if="!assignments.length">本科室暂无待交接随访。</p>
    <article v-for="row in assignments" :key="row.id">
      <p>任务 {{ row.id }} · {{ row.ownerUnavailable ? '负责人待安排' : '' }} {{ row.reviewerUnavailable ? '审核人待安排' : '' }}</p>
      <button v-if="pending !== row.id" class="secondary" type="button" @click="pending = row.id; nextOwner = 0; nextReviewer = 0">安排接替</button>
      <form v-else class="field-grid" @submit.prevent="reassign(row.id)">
        <label>接替负责人<select v-model.number="nextOwner">
          <option :value="0">保留当前负责人</option>
          <option v-for="person in staff.filter((s) => s.enabled && s.roles.includes('FOLLOWUP') && s.departmentIds.includes(selection))" :key="person.id" :value="person.id">{{ person.displayName }}</option>
        </select></label>
        <label>接替审核人<select v-model.number="nextReviewer">
          <option :value="0">保留当前审核人</option>
          <option v-for="person in staff.filter((s) => s.enabled && s.roles.includes('REVIEWER') && s.departmentIds.includes(selection))" :key="person.id" :value="person.id">{{ person.displayName }}</option>
        </select></label>
        <button :disabled="busy">确认交接</button>
      </form>
    </article>
    <button v-if="nextAssignment" class="secondary" type="button" :disabled="busy" @click="perform(() => loadAssignments(nextAssignment!))">载入更多任务</button>
  </section>
</template>

<style scoped>
.admin-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  gap: 24px;
}
.admin-form, .admin-filter, .admin-form label:not(:has(input[type="checkbox"])) {
  display: grid;
  gap: 8px;
}
.admin-form {
  align-content: start;
  gap: 14px;
}
.admin-form h3 { margin: 0; }
.admin-form fieldset {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  min-width: 0;
}
.admin-form button { justify-self: start; }
.admin-filter { max-width: 22rem; }
@media (max-width: 660px) { .admin-columns { grid-template-columns: 1fr; } }
</style>
