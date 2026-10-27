<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { listTables, problemDetail, runQuery, type QueryResponse, type TableSchema } from '@/api/warehouse'

const tables = ref<TableSchema[]>([])
const tablesError = ref('')

const route = useRoute()

// "在工作台打开" in the chat view passes the agent's final SQL as ?sql=.
const sql = ref(typeof route.query.sql === 'string' ? route.query.sql : 'SELECT c.name AS category, COUNT(*) AS orders\nFROM orders o\nJOIN order_items i ON i.order_id = o.id\nJOIN products p ON p.id = i.product_id\nJOIN categories c ON c.id = p.category_id\nGROUP BY c.name')
const running = ref(false)
const response = ref<QueryResponse | null>(null)
const queryError = ref('')

const tableColumns = computed(() =>
  (response.value?.result.columns ?? []).map((name, index) => ({
    title: name,
    dataIndex: String(index),
    key: String(index),
  })),
)

const tableRows = computed(() =>
  (response.value?.result.rows ?? []).map((row, rowIndex) => ({
    key: rowIndex,
    ...Object.fromEntries(row.map((value, index) => [String(index), value])),
  })),
)

onMounted(async () => {
  try {
    tables.value = await listTables()
  } catch (error) {
    tablesError.value = problemDetail(error)
  }
})

async function run() {
  running.value = true
  queryError.value = ''
  try {
    response.value = await runQuery(sql.value)
  } catch (error) {
    response.value = null
    queryError.value = problemDetail(error)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <a-layout class="workbench">
    <a-layout-sider width="300" theme="light" class="schema">
      <h3>数据表</h3>
      <a-alert v-if="tablesError" type="error" :message="tablesError" show-icon />
      <a-collapse ghost>
        <a-collapse-panel v-for="table in tables" :key="table.name">
          <template #header>
            <div class="table-name">{{ table.name }}</div>
            <div class="table-comment">{{ table.comment }}</div>
          </template>
          <ul class="columns">
            <li v-for="column in table.columns" :key="column.name">
              <code>{{ column.name }}</code>
              <span class="column-type">{{ column.type }}</span>
              <div v-if="column.comment" class="column-comment">{{ column.comment }}</div>
            </li>
          </ul>
        </a-collapse-panel>
      </a-collapse>
    </a-layout-sider>

    <a-layout-content class="console">
      <a-textarea v-model:value="sql" :rows="8" class="sql-input" />
      <a-space class="toolbar">
        <a-button type="primary" :loading="running" @click="run">运行</a-button>
        <span class="hint">只允许单条 SELECT，服务端会自动加上行数限制</span>
      </a-space>

      <a-alert v-if="queryError" type="error" :message="queryError" show-icon />
      <template v-if="response">
        <div class="executed">
          实际执行：<code>{{ response.sql }}</code>
          <span>· {{ response.result.rows.length }} 行 · {{ response.result.elapsedMs }} ms</span>
          <a-tag v-if="response.result.truncated" color="orange">结果已截断</a-tag>
        </div>
        <a-table :columns="tableColumns" :data-source="tableRows" size="small" :pagination="{ pageSize: 20 }" />
      </template>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.workbench {
  min-height: calc(100vh - 64px);
}

.schema {
  padding: 16px;
  border-right: 1px solid #f0f0f0;
  overflow: auto;
}

.table-name {
  font-weight: 500;
}

.table-comment,
.column-comment,
.column-type,
.hint {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}

.columns {
  list-style: none;
  padding: 0;
  margin: 0;
}

.columns li {
  padding: 4px 0;
}

.column-type {
  margin-left: 8px;
}

.console {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sql-input {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.executed {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}
</style>
