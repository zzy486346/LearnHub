<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <AppHeader />
    <main id="main-content" class="page-container"><RouterView /></main>
    <footer class="site-footer">
      <div class="footer-inner">
        <div>
          <RouterLink class="brand footer-brand" to="/courses"><span>问</span>问课尚学</RouterLink>
          <p>让知识可抵达，让成长有回响。</p>
        </div>
        <div class="footer-links" aria-label="页脚导航">
          <RouterLink to="/courses">探索课程</RouterLink>
          <RouterLink to="/questions">学习问答</RouterLink>
          <RouterLink to="/coupons">学习权益</RouterLink>
        </div>
      </div>
      <div class="footer-bottom">© 2026 LearnHub · 专注职业教育与实践成长</div>
    </footer>
  </div>
</template>
