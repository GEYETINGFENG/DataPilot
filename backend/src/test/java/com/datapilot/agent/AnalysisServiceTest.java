package com.datapilot.agent;

import com.datapilot.conversation.ConversationNotFoundException;
import com.datapilot.conversation.ConversationRepository;
import com.datapilot.conversation.StoredMessage;
import com.datapilot.warehouse.SchemaNotAllowedException;
import com.datapilot.warehouse.WarehouseProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {

	private final AnalysisAgent agent = mock(AnalysisAgent.class);
	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final WarehouseProperties warehouse = new WarehouseProperties("jdbc:mysql://unused", "u", "p",
			"demo_shop", List.of("demo_shop", "bird_db"), 15, 1000, Duration.ofMinutes(5));
	private final AgentExecutors executors = new AgentExecutors(Executors.newSingleThreadExecutor(),
			Executors.newSingleThreadExecutor());
	private final JsonMapper json = JsonMapper.builder().build();
	private final AnalysisService service = new AnalysisService(agent, conversations, warehouse, executors,
			AgentProperties.defaults(), json);

	@AfterEach
	void close() {
		executors.close();
	}

	@Test
	void databaseOutsideTheAllowlistIsRejectedBeforeAnythingRuns() {
		assertThatThrownBy(() -> service.start("q", null, "mysql", null))
				.isInstanceOf(SchemaNotAllowedException.class)
				.hasMessageContaining("mysql");
		verifyNoInteractions(conversations, agent);
	}

	@Test
	void allowedDatabaseReachesTheAgent() {
		when(conversations.create(anyString())).thenReturn(7L);
		when(agent.run(any(), any())).thenReturn(AnalysisOutcome.done("ok", "SELECT 1"));

		service.start("how many schools?", null, "bird_db", "evidence");

		ArgumentCaptor<AnalysisInput> input = ArgumentCaptor.forClass(AnalysisInput.class);
		verify(agent, timeout(2000)).run(input.capture(), any());
		assertThat(input.getValue().database()).isEqualTo("bird_db");
		assertThat(input.getValue().hint()).isEqualTo("evidence");
	}

	@Test
	void missingDatabaseMeansTheDefault() {
		when(conversations.create(anyString())).thenReturn(7L);
		when(agent.run(any(), any())).thenReturn(AnalysisOutcome.done("ok", "SELECT 1"));

		service.start("q", null, null, null);

		ArgumentCaptor<AnalysisInput> input = ArgumentCaptor.forClass(AnalysisInput.class);
		verify(agent, timeout(2000)).run(input.capture(), any());
		assertThat(input.getValue().database()).isEqualTo("demo_shop");
	}

	@Test
	void unknownConversationIsRejected() {
		when(conversations.exists(5L)).thenReturn(false);

		assertThatThrownBy(() -> service.start("q", 5L, null, null)).isInstanceOf(ConversationNotFoundException.class);
		verifyNoInteractions(agent);
	}

	@Test
	void bothMessagesAreStoredWithTheTraceOfTheTurn() {
		when(conversations.create(anyString())).thenReturn(7L);
		when(agent.run(any(), any())).thenAnswer(call -> {
			EventSink sink = call.getArgument(1);
			sink.emit(Events.TOKEN, new Events.Token("ok"));
			sink.emit(Events.DONE, new Events.Done("SELECT 1", "ok"));
			return AnalysisOutcome.done("ok", "SELECT 1");
		});

		service.start("q", null, null, null);

		ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
		verify(conversations, timeout(2000)).addMessage(eq(7L), eq(StoredMessage.ASSISTANT), eq("ok"), trace.capture());
		verify(conversations).addMessage(eq(7L), eq(StoredMessage.USER), eq("q"), isNull());
		// Tokens are left out: the conclusion is stored as the message content.
		assertThat(trace.getValue()).isEqualTo("""
				[{"event":"conversation","data":{"conversationId":7}},\
				{"event":"done","data":{"finalSql":"SELECT 1","conclusion":"ok"}}]""");
	}

	@Test
	void earlierTurnsBecomeHistoryWithTheirFinalSql() {
		Instant now = Instant.now();
		List<StoredMessage> messages = List.of(
				new StoredMessage("user", "华东呢？", null, now),
				new StoredMessage("assistant", "华东下降 5%", """
						[{"event":"done","data":{"finalSql":"SELECT 2","conclusion":"华东下降 5%"}}]""", now),
				new StoredMessage("user", "坏问题", null, now),
				new StoredMessage("assistant", "Analysis failed: boom", """
						[{"event":"error","data":{"message":"boom"}}]""", now));

		assertThat(service.turns(messages)).containsExactly(
				new Turn("华东呢？", "SELECT 2", "华东下降 5%"),
				new Turn("坏问题", null, null));
	}

	@Test
	void historyIsLoadedForAContinuedConversation() {
		when(conversations.exists(3L)).thenReturn(true);
		when(conversations.recentMessages(3L, 6)).thenReturn(List.of());
		when(agent.run(any(), any())).thenReturn(AnalysisOutcome.done("ok", "SELECT 1"));

		service.start("q", 3L, null, null);

		verify(conversations).recentMessages(3L, 6);
		verify(conversations, never()).create(anyString());
		verify(conversations, timeout(2000)).addMessage(eq(3L), eq(StoredMessage.ASSISTANT), anyString(), anyString());
		verify(conversations, never()).addMessage(anyLong(), eq(StoredMessage.ASSISTANT), isNull(), any());
	}

}
