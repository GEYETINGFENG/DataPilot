package com.datapilot.agent;

/**
 * One event of a turn as stored in {@code conversation_message.trace}: {"event": ..., "data": ...}.
 */
public record TraceEvent(String event, Object data) {
}
