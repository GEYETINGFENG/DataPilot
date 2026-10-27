package com.datapilot.conversation;

import java.time.Instant;

/**
 * @param trace the turn's events as a JSON array, assistant messages only
 */
public record StoredMessage(String role, String content, String trace, Instant createdAt) {

	public static final String USER = "user";
	public static final String ASSISTANT = "assistant";

}
