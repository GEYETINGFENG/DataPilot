<script setup lang="ts">
import type { TimelineItem, TurnState } from '@/analysis/timeline'
import SqlBlock from './SqlBlock.vue'
import ResultTable from './ResultTable.vue'
import ChartView from './ChartView.vue'

const { turn } = defineProps<{ turn: TurnState }>()

function dotColor(item: TimelineItem): string {
  if (item.kind !== 'query') return 'gray'
  const last = item.attempts[item.attempts.length - 1]
  if (!last || last.status === 'running') return turn.status === 'running' ? 'blue' : 'gray'
  return last.status === 'failed' ? 'red' : 'green'
}
</script>

<template>
  <a-timeline class="steps">
    <a-timeline-item v-for="item in turn.items" :key="item.key" :color="dotColor(item)">
      <template v-if="item.kind === 'plan'">
        <div class="label">思路</div>
        <div class="plan">{{ item.text }}</div>
      </template>

      <template v-else-if="item.kind === 'tables'">
        <div class="label">选用数据表</div>
        <a-tag v-for="table in item.tables" :key="table" class="table-tag">{{ table }}</a-tag>
      </template>

      <template v-else-if="item.kind === 'query'">
        <div class="label">第 {{ item.step }} 步查询</div>
        <div v-for="attempt in item.attempts" :key="attempt.attempt" class="attempt">
          <div class="attempt-head">
            <span>第 {{ attempt.attempt }} 次尝试</span>
            <a-tag v-if="attempt.attempt > 1" color="purple">自动修正</a-tag>
            <template v-if="attempt.status === 'running'">
              <a-tag v-if="turn.status === 'running'" color="processing">执行中</a-tag>
              <a-tag v-else>未完成</a-tag>
            </template>
            <a-tag v-else-if="attempt.status === 'failed'" color="error">失败</a-tag>
            <a-tag v-else color="success">成功</a-tag>
            <a-tag v-if="turn.finalSql && attempt.sql === turn.finalSql" color="gold">最终 SQL</a-tag>
          </div>
          <SqlBlock :sql="attempt.sql" :failed="attempt.status === 'failed'" />
          <a-alert v-if="attempt.error" class="attempt-error" type="error" :message="attempt.error" show-icon />
          <ResultTable v-if="attempt.result" class="attempt-result" :result="attempt.result" />
        </div>
      </template>

      <template v-else-if="item.kind === 'chart'">
        <div class="label">图表 · {{ item.title }}</div>
        <ChartView :option="item.option" />
      </template>
    </a-timeline-item>

    <a-timeline-item v-if="turn.status === 'running'" color="blue">
      <template #dot><a-spin size="small" /></template>
      <span class="label">{{ turn.conclusion ? '正在撰写结论…' : '分析中…' }}</span>
    </a-timeline-item>
  </a-timeline>
</template>

<style scoped>
.steps {
  padding-top: 8px;
}

.steps :deep(.ant-timeline-item:last-child) {
  padding-bottom: 0;
}

.label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 4px;
}

.plan {
  white-space: pre-wrap;
}

.table-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.attempt + .attempt {
  margin-top: 12px;
}

.attempt-head {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  margin-bottom: 4px;
}

.attempt-head > span {
  margin-right: 4px;
}

.attempt-error,
.attempt-result {
  margin-top: 6px;
}
</style>
