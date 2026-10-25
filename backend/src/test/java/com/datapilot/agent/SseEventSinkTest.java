package com.datapilot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventSinkTest {

	/**
	 * Records what would go over the wire; {@code failAfter} simulates a client that disconnects.
	 */
	static class FakeEmitter extends SseEmitter {

		final List<String> sent = new ArrayList<>();
		boolean completed;
		int failAfter = Integer.MAX_VALUE;

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			if (sent.size() >= failAfter) {
				throw new IOException("Broken pipe");
			}
			sent.add(builder.build().iterator().next().getData().toString());
		}

		@Override
		public void complete() {
			completed = true;
		}

	}

	@Test
	void terminalEventCompletesTheStreamAndNothingFollowsIt() {
		FakeEmitter emitter = new FakeEmitter();
		SseEventSink sink = new SseEventSink(emitter);

		sink.emit(Events.TOKEN, new Events.Token("a"));
		sink.emit(Events.DONE, new Events.Done("SELECT 1", "a"));
		sink.emit(Events.ERROR, new Events.Error("late"));

		assertThat(emitter.completed).isTrue();
		assertThat(sink.isClosed()).isTrue();
		assertThat(sink.trace()).extracting(TraceEvent::event).containsExactly("done");
	}

	@Test
	void disconnectClosesTheSinkButTheTraceStillEnds() {
		FakeEmitter emitter = new FakeEmitter();
		emitter.failAfter = 1;
		SseEventSink sink = new SseEventSink(emitter);

		sink.emit(Events.PLAN, new Events.Plan("p"));
		assertThat(sink.isClosed()).isFalse();
		sink.emit(Events.TABLES, new Events.Tables(List.of("orders")));
		assertThat(sink.isClosed()).isTrue();
		sink.emit(Events.ERROR, new Events.Error("Analysis cancelled"));

		assertThat(sink.trace()).extracting(TraceEvent::event).containsExactly("plan", "tables", "error");
	}

}
