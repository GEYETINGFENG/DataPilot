<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <a-layout class="app">
    <a-layout-header class="header">
      <span class="logo">DataPilot</span>
      <span class="tagline">AI 数据分析智能体</span>
    </a-layout-header>
    <a-layout-content>
      <RouterView />
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.app {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
  padding: 0 24px;
}

.logo {
  font-size: 18px;
  font-weight: 600;
}

.tagline {
  color: rgba(0, 0, 0, 0.45);
}
</style>
