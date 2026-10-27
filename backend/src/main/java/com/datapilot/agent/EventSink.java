package com.datapilot.agent;

/**
 * Where a running turn reports progress. In production this is the SSE stream; in tests a list.
 * <p>
 * Implementations must be thread-safe: drill-down steps report from several threads at once.
 */
public interface EventSink {

	/**
	 * @param event one of the names in {@link Events}
	 * @param data  the matching payload record
	 */
	void emit(String event, Object data);

	/**
	 * True once nobody is listening any more (the client disconnected or the stream timed out).
	 * The agent checks this between steps and stops spending tokens.
	 */
	boolean isClosed();

}
