<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from './api'

const router = useRouter()
const username = ref('')
const password = ref('')
const failure = ref('')
const busy = ref(false)

async function submit() {
  failure.value = ''
  busy.value = true
  try {
    await login(username.value, password.value)
    await router.replace('/workspace')
  } catch (error) {
    failure.value = (error as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="sign-in">
    <section class="journey" aria-labelledby="journey-heading">
      <h1 id="journey-heading">门诊与出院随访</h1>
      <p>查看预约、随访安排和复诊记录。</p>
      <ol class="journey-steps">
        <li><span>门诊</span><span>预约与接诊</span></li>
        <li><span>出院后</span><span>查看随访安排</span></li>
        <li><span>复诊</span><span>预约与结果核实</span></li>
      </ol>
    </section>
    <section class="credentials" aria-labelledby="login-heading">
      <form class="sign-in-form" @submit.prevent="submit">
        <h2 id="login-heading">登录</h2>
        <p>请使用本地演示账号。</p>
        <label for="username">账号</label>
        <input id="username" v-model.trim="username" autocomplete="username" required />
        <label for="password">密码</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password" required />
        <p v-if="failure" role="alert" class="error">{{ failure }}</p>
        <button type="submit" :disabled="busy">{{ busy ? '正在登录' : '登录' }}</button>
      </form>
    </section>
  </main>
</template>
