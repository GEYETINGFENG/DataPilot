package com.datapilot.agent;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * The model tiers the agent can use. Which one a node uses is decided in code from the
 * {@link Route}: cheap, fast calls (routing, table selection, chart layout) always use the fast
 * model; SQL and the conclusion use the reasoning model only for analysis questions.
 */
public record AgentModels(ChatModel fast, ChatModel reasoning,
		StreamingChatModel fastStreaming, StreamingChatModel reasoningStreaming) {

	public ChatModel forRoute(Route route) {
		return route == Route.ANALYSIS ? reasoning : fast;
	}

	public StreamingChatModel streamingForRoute(Route route) {
		return route == Route.ANALYSIS ? reasoningStreaming : fastStreaming;
	}

	/**
	 * One prompt in, the reply text out. Each node call is a single self-contained prompt: the
	 * graph state, not a chat transcript, carries what earlier steps learned.
	 */
	public static String ask(ChatModel model, String prompt) {
		return model.chat(UserMessage.from(prompt)).aiMessage().text();
	}

}
