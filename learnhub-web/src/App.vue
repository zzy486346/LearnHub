<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
</script>

<template>
  <div class="app-shell">
    <AppHeader />
    <main class="page-container"><RouterView /></main>
    <footer>问课尚学 · 让每一次提问都抵达答案</footer>
  </div>
</template>
