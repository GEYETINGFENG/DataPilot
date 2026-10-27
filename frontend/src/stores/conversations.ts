import { defineStore } from 'pinia'
import { reactive, ref, watch } from 'vue'
import { getConversationMessages, streamAnalysis } from '@/api/analysis'
import { problemDetail } from '@/api/warehouse'
import { applyEvent, createTurn, replayMessage, type TurnState } from '@/analysis/timeline'

export interface ConversationMeta {
  id: number
  title: string
  updatedAt: string
}

export interface ChatTurn {
  id: string
  question: string
  assistant: TurnState
}

// The backend has no endpoint listing conversations, so the ids this browser created are
// remembered locally. Storage can be unavailable (private mode, blocked site data).
const STORAGE_KEY = 'datapilot.conversations'

function loadConversations(): ConversationMeta[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((c) => typeof c?.id === 'number') : []
  } catch {
    return []
  }
}

function saveConversations(conversations: ConversationMeta[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations))
  } catch {
    // Not persisted; the list still works for this page load.
  }
}

let turnSeq = 0

export const useConversationsStore = defineStore('conversations', () => {
  const conversations = ref<ConversationMeta[]>(loadConversations())
  const activeId = ref<number | null>(null)
  const turns = ref<ChatTurn[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const running = ref(false)
  let controller: AbortController | null = null

  watch(conversations, saveConversations, { deep: true })

  function touch(id: number, title: string) {
    const existing = conversations.value.find((c) => c.id === id)
    const updatedAt = new Date().toISOString()
    if (existing) {
      existing.updatedAt = updatedAt
    } else {
      conversations.value.unshift({ id, title, updatedAt })
    }
  }

  function stop() {
    controller?.abort()
  }

  function newConversation() {
    stop()
    activeId.value = null
    turns.value = []
    loadError.value = ''
  }

  async function select(id: number) {
    if (id === activeId.value && !loadError.value) return
    stop()
    activeId.value = id
    turns.value = []
    loadError.value = ''
    loading.value = true
    try {
      const messages = await getConversationMessages(id)
      if (activeId.value !== id) return
      const loaded: ChatTurn[] = []
      let question = ''
      for (const message of messages) {
        if (message.role === 'user') {
          question = message.content
        } else {
          loaded.push({ id: `t${++turnSeq}`, question, assistant: replayMessage(message) })
          question = ''
        }
      }
      turns.value = loaded
    } catch (error) {
      if (activeId.value === id) loadError.value = problemDetail(error)
    } finally {
      if (activeId.value === id) loading.value = false
    }
  }

  function remove(id: number) {
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) newConversation()
  }

  async function ask(question: string) {
    if (running.value || !question.trim()) return
    const turn: ChatTurn = { id: `t${++turnSeq}`, question, assistant: reactive(createTurn()) }
    turns.value.push(turn)
    const assistant = turn.assistant
    const own = new AbortController()
    controller = own
    running.value = true
    const startedIn = activeId.value
    try {
      await streamAnalysis(
        { question, conversationId: activeId.value },
        {
          signal: own.signal,
          onEvent(event) {
            applyEvent(assistant, event)
            if (event.event === 'conversation') {
              touch(event.data.conversationId, question)
              // Only follow the new id if the user is still looking at this conversation.
              if (activeId.value === startedIn) activeId.value = event.data.conversationId
            }
          },
        },
      )
    } catch (error) {
      if (assistant.status === 'running') {
        if (error instanceof DOMException && error.name === 'AbortError') {
          assistant.status = 'aborted'
        } else {
          assistant.status = 'error'
          assistant.error = problemDetail(error)
        }
      }
    } finally {
      if (controller === own) {
        running.value = false
        controller = null
      }
    }
  }

  return { conversations, activeId, turns, loading, loadError, running, ask, stop, newConversation, select, remove }
})
