<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'

const mock = import.meta.env.VITE_MOCK === '1'
</script>

<template>
  <a-layout class="app">
    <a-layout-header class="header">
      <span class="logo">DataPilot</span>
      <span class="tagline">AI 数据分析智能体</span>
      <nav class="nav">
        <RouterLink to="/" class="nav-link" exact-active-class="active">分析对话</RouterLink>
        <RouterLink to="/workbench" class="nav-link" active-class="active">SQL 工作台</RouterLink>
      </nav>
      <a-tag v-if="mock" color="orange" class="mock-tag">Mock 数据</a-tag>
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

.nav {
  display: flex;
  gap: 20px;
  margin-left: 32px;
}

.nav-link {
  color: rgba(0, 0, 0, 0.65);
}

.nav-link.active {
  color: #1677ff;
  font-weight: 500;
}

.mock-tag {
  margin-left: auto;
}
</style>
