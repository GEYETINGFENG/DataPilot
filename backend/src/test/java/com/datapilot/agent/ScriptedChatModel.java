package com.datapilot.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fake model that answers from a script instead of calling an LLM.
 * <p>
 * Replies are chosen by the prompt's task heading (each template in {@code prompts/} starts with
 * {@code # Task: ...}), not by call order, because drill-down steps call the model from several
 * threads. Each task's replies are used in order; the last one repeats.
 */
class ScriptedChatModel implements ChatModel {

	static final String ROUTE = "# Task: classify the question";
	static final String SELECT_TABLES = "# Task: choose tables";
	static final String SQL = "# Task: write one MySQL query";
	static final String DRILL_DOWN = "# Task: plan the drill-down";
	static final String CHART = "# Task: design charts";
	static final String CONCLUDE = "# Task: write the conclusion";

	private final Map<String, Deque<String>> script = new LinkedHashMap<>();
	private final List<String> prompts = new ArrayList<>();

	ScriptedChatModel on(String task, String... replies) {
		script.put(task, new ArrayDeque<>(List.of(replies)));
		return this;
	}

	/**
	 * Every prompt received for the task, in order.
	 */
	synchronized List<String> prompts(String task) {
		return prompts.stream().filter(p -> p.startsWith(task)).toList();
	}

	private synchronized String reply(ChatRequest request) {
		String prompt = lastUserText(request.messages());
		prompts.add(prompt);
		for (Map.Entry<String, Deque<String>> entry : script.entrySet()) {
			if (prompt.startsWith(entry.getKey())) {
				Deque<String> replies = entry.getValue();
				return replies.size() > 1 ? replies.poll() : replies.peek();
			}
		}
		throw new AssertionError("Unexpected prompt:\n" + prompt);
	}

	@Override
	public ChatResponse doChat(ChatRequest request) {
		return ChatResponse.builder().aiMessage(AiMessage.from(reply(request))).build();
	}

	/**
	 * The same script as a streaming model: the reply arrives in 4-character chunks.
	 */
	StreamingChatModel streaming() {
		return new StreamingChatModel() {
			@Override
			public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
				stream(request, handler);
			}
		};
	}

	private void stream(ChatRequest request, StreamingChatResponseHandler handler) {
		String text = reply(request);
		StreamingHandle handle = new StreamingHandle() {
			@Override
			public void cancel() {
			}

			@Override
			public boolean isCancelled() {
				return false;
			}
		};
		for (int i = 0; i < text.length(); i += 4) {
			handler.onPartialResponse(new PartialResponse(text.substring(i, Math.min(text.length(), i + 4))),
					new PartialResponseContext(handle));
		}
		handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
	}

	private static String lastUserText(List<ChatMessage> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof UserMessage user) {
				return user.singleText();
			}
		}
		throw new AssertionError("No user message");
	}

}
