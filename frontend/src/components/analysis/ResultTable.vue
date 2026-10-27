<script setup lang="ts">
import { computed } from 'vue'
import type { QueryResult } from '@/api/warehouse'

const props = defineProps<{ result: QueryResult }>()

const columns = computed(() =>
  props.result.columns.map((name, index) => ({
    title: name,
    dataIndex: String(index),
    key: String(index),
    ellipsis: true,
  })),
)

const rows = computed(() =>
  props.result.rows.map((row, rowIndex) => ({
    key: rowIndex,
    ...Object.fromEntries(row.map((value, index) => [String(index), value ?? 'NULL'])),
  })),
)

const pagination = computed(() =>
  props.result.rows.length > 5 ? { pageSize: 5, size: 'small' as const, showSizeChanger: false } : false,
)
</script>

<template>
  <div class="result">
    <div class="meta">
      {{ result.rows.length }} 行 · {{ result.elapsedMs }} ms
      <a-tag v-if="result.truncated" color="orange">结果已截断</a-tag>
    </div>
    <a-table
      :columns="columns"
      :data-source="rows"
      size="small"
      :pagination="pagination"
      :scroll="{ x: 'max-content' }"
      bordered
    />
  </div>
</template>

<style scoped>
.meta {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 6px;
}

.result :deep(.ant-table) {
  font-size: 12px;
}
</style>
