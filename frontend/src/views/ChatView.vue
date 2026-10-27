<script setup lang="ts">
import { nextTick, ref, useTemplateRef, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useConversationsStore } from '@/stores/conversations'
import AssistantTurn from '@/components/analysis/AssistantTurn.vue'

const EXAMPLES = [
  '2026 年第三季度哪个品类的复购率下降最多？为什么？',
  '2026 年各大区的销售额和订单量分别是多少？',
  '退款最多的品类是哪个？主要退款原因是什么？',
  'App 注册用户的客单价和其他渠道相比如何？',
]

const store = useConversationsStore()
const { conversations, activeId, turns, loading, loadError, running } = storeToRefs(store)

const draft = ref('')
const messages = useTemplateRef<HTMLDivElement>('messages')

function send(question = draft.value) {
  const text = question.trim()
  if (!text || running.value) return
  draft.value = ''
  store.ask(text)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}

function nearBottom(el: HTMLElement) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

// Follow the live run while the user is at the bottom; leave them alone if they scrolled up.
watch(
  turns,
  async () => {
    const el = messages.value
    if (!el) return
    const follow = nearBottom(el)
    await nextTick()
    if (follow) el.scrollTop = el.scrollHeight
  },
  { deep: true, flush: 'pre' },
)

function formatTime(iso: string) {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <a-layout class="chat">
    <a-layout-sider width="260" theme="light" class="sidebar">
      <a-button type="primary" block @click="store.newConversation()">+ 新建对话</a-button>
      <div class="sidebar-title">历史对话</div>
      <div v-if="!conversations.length" class="sidebar-empty">还没有对话</div>
      <ul class="conversation-list">
        <li
          v-for="conversation in conversations"
          :key="conversation.id"
          :class="{ active: conversation.id === activeId }"
          @click="store.select(conversation.id)"
        >
          <div class="conversation-title">{{ conversation.title }}</div>
          <div class="conversation-meta">
            <span>{{ formatTime(conversation.updatedAt) }}</span>
            <a-popconfirm title="从列表中移除这个对话？" ok-text="移除" cancel-text="取消" @confirm="store.remove(conversation.id)">
              <a class="remove" @click.stop>移除</a>
            </a-popconfirm>
          </div>
        </li>
      </ul>
      <div class="sidebar-note">对话列表保存在本浏览器中</div>
    </a-layout-sider>

    <a-layout-content class="main">
      <div ref="messages" class="messages">
        <div class="thread">
          <a-spin v-if="loading" class="loading" tip="加载对话…" />
          <a-alert v-if="loadError" type="error" show-icon message="加载对话失败" :description="loadError" />

          <div v-if="!turns.length && !loading && !loadError" class="empty">
            <h2>用自然语言分析你的数据</h2>
            <p>DataPilot 会自己选表、写 SQL、执行并在出错时自我纠错，按维度下钻后给出图表和结论。每一步都实时可见。</p>
            <div class="examples">
              <a-button v-for="example in EXAMPLES" :key="example" shape="round" class="example" @click="send(example)">
                {{ example }}
              </a-button>
            </div>
          </div>

          <div v-for="turn in turns" :key="turn.id" class="turn">
            <div v-if="turn.question" class="question">{{ turn.question }}</div>
            <AssistantTurn :turn="turn.assistant" />
          </div>
        </div>
      </div>

      <div class="composer">
        <div class="composer-inner">
          <a-textarea
            v-model:value="draft"
            :auto-size="{ minRows: 1, maxRows: 6 }"
            placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
            @keydown="onKeydown"
          />
          <a-button v-if="running" danger @click="store.stop()">停止</a-button>
          <a-button v-else type="primary" :disabled="!draft.trim()" @click="send()">发送</a-button>
        </div>
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.chat {
  height: calc(100vh - 64px);
}

.sidebar {
  border-right: 1px solid #f0f0f0;
}

.sidebar :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
}

.sidebar-title {
  margin: 20px 4px 8px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.sidebar-empty,
.sidebar-note {
  padding: 0 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.35);
}

.sidebar-note {
  margin-top: auto;
  padding-top: 12px;
}

.conversation-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
}

.conversation-list li {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}

.conversation-list li:hover {
  background: rgba(0, 0, 0, 0.04);
}

.conversation-list li.active {
  background: #e6f4ff;
}

.conversation-title {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.conversation-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.35);
}

.remove {
  visibility: hidden;
  color: rgba(0, 0, 0, 0.45);
}

.conversation-list li:hover .remove {
  visibility: visible;
}

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.messages {
  flex: 1;
  overflow-y: auto;
}

.thread {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.loading {
  align-self: center;
}

.empty {
  margin-top: 12vh;
  text-align: center;
}

.empty h2 {
  font-size: 24px;
  margin-bottom: 8px;
}

.empty p {
  color: rgba(0, 0, 0, 0.45);
  max-width: 560px;
  margin: 0 auto 24px;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.example {
  height: auto;
  white-space: normal;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.question {
  align-self: flex-end;
  max-width: 80%;
  padding: 8px 14px;
  border-radius: 12px 12px 2px 12px;
  background: #1677ff;
  color: #fff;
  white-space: pre-wrap;
}

.composer {
  border-top: 1px solid #f0f0f0;
  background: #fff;
  padding: 12px 24px;
}

.composer-inner {
  max-width: 960px;
  margin: 0 auto;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
</style>
