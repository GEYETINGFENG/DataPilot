<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { summarize, type TurnState } from '@/analysis/timeline'
import StepTimeline from './StepTimeline.vue'
import MarkdownView from './MarkdownView.vue'

const props = defineProps<{ turn: TurnState }>()
const router = useRouter()

const summary = computed(() => summarize(props.turn))
// Expanded by default: watching the agent work is the point of the UI.
const expanded = ref<string[]>(['steps'])

const header = computed(() => {
  const parts = [`分析过程 · ${props.turn.items.length} 个步骤 · ${summary.value.queries} 条查询`]
  if (summary.value.corrections) parts.push(`${summary.value.corrections} 次自动纠错`)
  return parts.join(' · ')
})

async function copyFinalSql() {
  if (!props.turn.finalSql) return
  try {
    await navigator.clipboard.writeText(props.turn.finalSql)
    message.success('SQL 已复制')
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}

function openInWorkbench() {
  if (props.turn.finalSql) router.push({ name: 'workbench', query: { sql: props.turn.finalSql } })
}
</script>

<template>
  <div class="assistant-turn">
    <a-collapse v-if="turn.items.length || turn.status === 'running'" v-model:active-key="expanded" class="process">
      <a-collapse-panel key="steps">
        <template #header>
          <span class="process-header">
            <a-spin v-if="turn.status === 'running'" size="small" />
            {{ header }}
          </span>
        </template>
        <StepTimeline :turn="turn" />
      </a-collapse-panel>
    </a-collapse>

    <a-card v-if="turn.conclusion" size="small" class="conclusion" :bordered="false">
      <MarkdownView :source="turn.conclusion" />
      <span v-if="turn.status === 'running'" class="cursor"></span>
    </a-card>

    <a-alert v-if="turn.status === 'error'" type="error" show-icon message="分析失败" :description="turn.error ?? ''" />
    <a-alert v-if="turn.status === 'aborted'" type="warning" show-icon message="已停止本次分析" />

    <a-space v-if="turn.finalSql" class="actions">
      <a-button size="small" @click="copyFinalSql">复制 SQL</a-button>
      <a-button size="small" @click="openInWorkbench">在工作台打开</a-button>
    </a-space>
  </div>
</template>

<style scoped>
.assistant-turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.process {
  background: #fff;
}

.process-header {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(0, 0, 0, 0.65);
}

.conclusion {
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  background: #1677ff;
  vertical-align: text-bottom;
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}
</style>
