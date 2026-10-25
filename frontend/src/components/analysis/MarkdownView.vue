<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ source: string }>()

// The conclusion is model output: render it as Markdown, then strip anything executable.
const html = computed(() => DOMPurify.sanitize(marked.parse(props.source, { async: false })))
</script>

<template>
  <div class="markdown" v-html="html"></div>
</template>

<style scoped>
.markdown {
  line-height: 1.75;
}

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  font-size: 15px;
  font-weight: 600;
  margin: 16px 0 8px;
}

.markdown :deep(:first-child) {
  margin-top: 0;
}

.markdown :deep(p),
.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 8px;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 20px;
}

.markdown :deep(code) {
  background: rgba(0, 0, 0, 0.04);
  padding: 1px 4px;
  border-radius: 4px;
}

.markdown :deep(table) {
  border-collapse: collapse;
  margin-bottom: 8px;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid #f0f0f0;
  padding: 4px 8px;
}
</style>
