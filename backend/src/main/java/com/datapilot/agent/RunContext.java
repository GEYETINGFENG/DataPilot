package com.datapilot.agent;

import org.bsc.langgraph4j.RunnableConfig;

/**
 * Per-turn services the nodes need but that are not analysis data.
 * <p>
 * It travels in the graph's {@link RunnableConfig} metadata instead of the graph state: state is
 * plain, serializable data (question, tables, results), while the event sink is a live connection.
 */
public record RunContext(EventSink events) {

	static final String KEY = "datapilot.run";

	public static RunContext of(RunnableConfig config) {
		return (RunContext) config.metadata(KEY)
				.orElseThrow(() -> new IllegalStateException("RunContext missing from RunnableConfig"));
	}

	public void emit(String event, Object data) {
		events.emit(event, data);
	}

	/**
	 * Called before every model call or query, the expensive operations.
	 */
	public void checkCancelled() {
		if (events.isClosed()) {
			throw new RunCancelledException();
		}
	}

}
