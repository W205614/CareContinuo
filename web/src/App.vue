<template>
  <header class="site-header">
    <RouterLink class="brand" :to="$route.path === '/workspace' ? '/workspace' : '/'">
      <span>CareContinuo</span> <span class="brand-name">诊程桥</span>
    </RouterLink>
  </header>
  <RouterView />
</template>
