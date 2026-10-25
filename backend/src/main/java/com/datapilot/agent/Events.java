package com.datapilot.agent;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * SSE event names and payloads, exactly as specified in {@code docs/analysis-api.md}.
 * The UI and the evaluation harness parse these, so change them only together with the contract.
 */
public final class Events {

	public static final String CONVERSATION = "conversation";
	public static final String PLAN = "plan";
	public static final String TABLES = "tables";
	public static final String SQL = "sql";
	public static final String SQL_ERROR = "sql_error";
	public static final String RESULT = "result";
	public static final String CHART = "chart";
	public static final String TOKEN = "token";
	public static final String DONE = "done";
	public static final String ERROR = "error";

	private Events() {
	}

	public static boolean isTerminal(String event) {
		return DONE.equals(event) || ERROR.equals(event);
	}

	public record Conversation(long conversationId) {
	}

	public record Plan(String text) {
	}

	public record Tables(List<String> tables) {
	}

	public record Sql(int step, int attempt, String sql) {
	}

	public record SqlError(int step, int attempt, String message) {
	}

	public record Result(int step, int attempt, List<String> columns, List<List<Object>> rows,
			boolean truncated, long elapsedMs) {
	}

	public record Chart(String title, JsonNode option) {
	}

	public record Token(String text) {
	}

	public record Done(String finalSql, String conclusion) {
	}

	public record Error(String message) {
	}

}
