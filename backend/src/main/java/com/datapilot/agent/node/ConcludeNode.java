package com.datapilot.agent.node;

import com.datapilot.agent.AgentModels;
import com.datapilot.agent.AgentProperties;
import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.Events;
import com.datapilot.agent.HistoryText;
import com.datapilot.agent.Prompts;
import com.datapilot.agent.RunCancelledException;
import com.datapilot.agent.RunContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Writes the conclusion in Markdown, streamed to the client as {@code token} events.
 * <p>
 * Streaming matters here because this is the longest model output of the turn; everything else
 * the user waits for already shows up as {@code sql} / {@code result} events.
 */
@Component
public class ConcludeNode implements NodeActionWithConfig<AnalysisState> {

	private static final long POLL_MILLIS = 500;

	private final AgentModels models;
	private final Prompts prompts;
	private final AgentProperties props;

	public ConcludeNode(AgentModels models, Prompts prompts, AgentProperties props) {
		this.models = models;
		this.prompts = prompts;
		this.props = props;
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) throws Exception {
		RunContext run = RunContext.of(config);
		run.checkCancelled();
		String prompt = prompts.render(Prompts.CONCLUDE, Map.of(
				"question", state.question(),
				"hint", state.hint().orElse(""),
				"history", HistoryText.render(state.history()),
				"findings", Findings.describe(state.steps(), props.previewRows())));

		CompletableFuture<String> conclusion = new CompletableFuture<>();
		models.streamingForRoute(state.route()).chat(
				ChatRequest.builder().messages(UserMessage.from(prompt)).build(),
				new StreamingChatResponseHandler() {
					@Override
					public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
						if (run.events().isClosed()) {
							context.streamingHandle().cancel();
							conclusion.cancel(false);
							return;
						}
						run.emit(Events.TOKEN, new Events.Token(partial.text()));
					}

					@Override
					public void onCompleteResponse(ChatResponse response) {
						conclusion.complete(response.aiMessage().text());
					}

					@Override
					public void onError(Throwable error) {
						conclusion.completeExceptionally(error);
					}
				});
		return Map.of(AnalysisState.CONCLUSION, await(conclusion, run));
	}

	/**
	 * Waits for the stream to finish, checking now and then whether the client is still there.
	 */
	private static String await(CompletableFuture<String> conclusion, RunContext run) throws Exception {
		while (true) {
			run.checkCancelled();
			try {
				return conclusion.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// keep waiting
			} catch (CancellationException e) {
				throw new RunCancelledException();
			} catch (ExecutionException e) {
				throw e.getCause() instanceof Exception cause ? cause : e;
			}
		}
	}

}
