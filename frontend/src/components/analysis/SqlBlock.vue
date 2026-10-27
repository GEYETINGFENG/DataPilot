<script setup lang="ts">
import { computed } from 'vue'
import { message } from 'ant-design-vue'
import hljs from 'highlight.js/lib/core'
import sqlLanguage from 'highlight.js/lib/languages/sql'
import 'highlight.js/styles/github.css'

hljs.registerLanguage('sql', sqlLanguage)

const props = defineProps<{ sql: string; failed?: boolean }>()

// highlight.js escapes the source, so its output is safe for v-html.
const html = computed(() => hljs.highlight(props.sql, { language: 'sql' }).value)

async function copy() {
  try {
    await navigator.clipboard.writeText(props.sql)
    message.success('SQL 已复制')
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="sql-block" :class="{ failed }">
    <a-button class="copy" size="small" type="text" @click="copy">复制 SQL</a-button>
    <pre><code class="hljs language-sql" v-html="html"></code></pre>
  </div>
</template>

<style scoped>
.sql-block {
  position: relative;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  background: #fafafa;
}

.sql-block.failed {
  border-color: #ffccc7;
  background: #fff7f6;
}

pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
}

pre .hljs {
  background: transparent;
  padding: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.copy {
  position: absolute;
  top: 4px;
  right: 4px;
  font-size: 12px;
}
</style>
