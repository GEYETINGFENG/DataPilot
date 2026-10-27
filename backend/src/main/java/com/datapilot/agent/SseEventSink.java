package com.datapilot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends a turn's events over SSE and records them for the stored trace.
 * <p>
 * The stream must always end with {@code done} or {@code error}. Three things can end it:
 * <ul>
 *   <li>the agent finishes and emits a terminal event: it is sent and the stream completed;</li>
 *   <li>the stream times out: an {@code error} is sent right away and the agent, which sees
 *       {@link #isClosed()}, stops at its next checkpoint;</li>
 *   <li>the client disconnects: the next send fails, the sink marks itself closed and the agent
 *       stops. Its {@code error} event can no longer be delivered, but it still ends the trace.</li>
 * </ul>
 * Once a terminal event has been recorded everything else is ignored, so there is exactly one.
 */
public class SseEventSink implements EventSink {

	private static final Logger log = LoggerFactory.getLogger(SseEventSink.class);

	private final SseEmitter emitter;
	private final List<TraceEvent> trace = new ArrayList<>();
	private volatile boolean closed;
	private boolean terminated;

	public SseEventSink(SseEmitter emitter) {
		this.emitter = emitter;
		emitter.onTimeout(() -> emit(Events.ERROR, new Events.Error("Analysis timed out")));
		emitter.onError(e -> closed = true);
		emitter.onCompletion(() -> closed = true);
	}

	@Override
	public synchronized void emit(String event, Object data) {
		if (terminated) {
			return;
		}
		if (!Events.TOKEN.equals(event)) {
			trace.add(new TraceEvent(event, data));
		}
		if (!closed) {
			try {
				emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
			} catch (IOException | IllegalStateException e) {
				log.info("Client went away, stopping the analysis: {}", e.getMessage());
				closed = true;
			}
		}
		if (Events.isTerminal(event)) {
			terminated = true;
			closed = true;
			try {
				emitter.complete();
			} catch (IllegalStateException e) {
				// already completed by the container
			}
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	/**
	 * Every event so far except {@code token}, in order.
	 */
	public synchronized List<TraceEvent> trace() {
		return List.copyOf(trace);
	}

}
