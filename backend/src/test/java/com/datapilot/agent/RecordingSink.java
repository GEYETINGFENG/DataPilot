package com.datapilot.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects events in memory. {@link #closeAfter} simulates a client that disconnects.
 */
class RecordingSink implements EventSink {

	private final List<TraceEvent> events = new ArrayList<>();
	private volatile boolean closed;
	private String closeAfter;

	RecordingSink closeAfter(String event) {
		this.closeAfter = event;
		return this;
	}

	@Override
	public synchronized void emit(String event, Object data) {
		events.add(new TraceEvent(event, data));
		if (event.equals(closeAfter)) {
			closed = true;
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	synchronized List<TraceEvent> events() {
		return List.copyOf(events);
	}

	/**
	 * Event names in order, with consecutive tokens collapsed into one, for readable assertions.
	 */
	synchronized List<String> names() {
		List<String> names = new ArrayList<>();
		for (TraceEvent event : events) {
			if (!(event.event().equals(Events.TOKEN) && !names.isEmpty() && names.get(names.size() - 1).equals(Events.TOKEN))) {
				names.add(event.event());
			}
		}
		return names;
	}

	@SuppressWarnings("unchecked")
	synchronized <T> List<T> data(String event, Class<T> type) {
		return events.stream().filter(e -> e.event().equals(event)).map(e -> (T) e.data()).toList();
	}

	String tokens() {
		return String.join("", data(Events.TOKEN, Events.Token.class).stream().map(Events.Token::text).toList());
	}

}
