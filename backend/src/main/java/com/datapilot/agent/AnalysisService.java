package com.datapilot.agent;

import com.datapilot.conversation.ConversationNotFoundException;
import com.datapilot.conversation.ConversationRepository;
import com.datapilot.conversation.StoredMessage;
import com.datapilot.warehouse.WarehouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for one analysis turn: validates the request, records the conversation, and runs
 * the agent on a background thread while events stream to the client.
 * <p>
 * Everything that can be rejected (unknown database, unknown conversation, server busy) is checked
 * before the stream starts, so those failures are plain HTTP problem responses, not SSE events.
 */
@Service
public class AnalysisService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
	private static final int MAX_TITLE_LENGTH = 100;

	private final AnalysisAgent agent;
	private final ConversationRepository conversations;
	private final WarehouseProperties warehouse;
	private final AgentExecutors executors;
	private final AgentProperties props;
	private final JsonMapper json;

	public AnalysisService(AnalysisAgent agent, ConversationRepository conversations, WarehouseProperties warehouse,
			AgentExecutors executors, AgentProperties props, JsonMapper json) {
		this.agent = agent;
		this.conversations = conversations;
		this.warehouse = warehouse;
		this.executors = executors;
		this.props = props;
		this.json = json;
	}

	/**
	 * @param conversationId null starts a new conversation
	 * @param database       null means the default schema
	 * @throws com.datapilot.warehouse.SchemaNotAllowedException if the database is not allowed
	 * @throws ConversationNotFoundException                    if the conversation does not exist
	 * @throws java.util.concurrent.RejectedExecutionException  if too many turns are running
	 */
	public SseEmitter start(String question, Long conversationId, String database, String hint) {
		String schema = warehouse.resolveSchema(database);
		List<Turn> history = List.of();
		long id;
		if (conversationId == null) {
			id = conversations.create(title(question));
		} else {
			id = conversationId;
			if (!conversations.exists(id)) {
				throw new ConversationNotFoundException(id);
			}
			history = turns(conversations.recentMessages(id, props.historyTurns() * 2));
		}

		SseEmitter emitter = new SseEmitter(props.streamTimeout().toMillis());
		SseEventSink sink = new SseEventSink(emitter);
		AnalysisInput input = new AnalysisInput(question, schema, hint, history);
		executors.runs().execute(() -> run(id, input, sink));
		return emitter;
	}

	private void run(long conversationId, AnalysisInput input, SseEventSink sink) {
		AnalysisOutcome outcome = AnalysisOutcome.failed("Analysis did not start");
		try {
			// First, before anything that can fail: the contract promises `conversation` comes first.
			sink.emit(Events.CONVERSATION, new Events.Conversation(conversationId));
			conversations.addMessage(conversationId, StoredMessage.USER, input.question(), null);
			outcome = agent.run(input, sink);
		} catch (RuntimeException e) {
			log.error("Analysis turn failed outside the agent", e);
			sink.emit(Events.ERROR, new Events.Error("Analysis failed: " + e.getMessage()));
		}
		try {
			String content = outcome.succeeded() ? outcome.conclusion() : outcome.error();
			conversations.addMessage(conversationId, StoredMessage.ASSISTANT, content,
					json.writeValueAsString(sink.trace()));
		} catch (RuntimeException e) {
			log.error("Could not store the answer of conversation {}", conversationId, e);
		}
	}

	/**
	 * Pairs stored messages into turns. The SQL that answered each turn is read back from the
	 * {@code done} event in its trace.
	 */
	List<Turn> turns(List<StoredMessage> messages) {
		List<Turn> turns = new ArrayList<>();
		String question = null;
		for (StoredMessage message : messages) {
			if (StoredMessage.USER.equals(message.role())) {
				question = message.content();
			} else if (question != null) {
				JsonNode done = doneEvent(message.trace());
				turns.add(done == null
						? new Turn(question, null, null)
						: new Turn(question, done.path("finalSql").asString(null), message.content()));
				question = null;
			}
		}
		return turns;
	}

	private JsonNode doneEvent(String trace) {
		if (trace == null) {
			return null;
		}
		try {
			for (JsonNode event : json.readTree(trace).values()) {
				if (Events.DONE.equals(event.path("event").asString(""))) {
					return event.path("data");
				}
			}
		} catch (JacksonException e) {
			log.warn("Unreadable trace: {}", e.getOriginalMessage());
		}
		return null;
	}

	private static String title(String question) {
		String title = question.strip();
		return title.codePointCount(0, title.length()) <= MAX_TITLE_LENGTH
				? title
				: title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH)) + "…";
	}

}
