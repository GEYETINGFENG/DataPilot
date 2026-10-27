package com.datapilot.agent;

import java.util.List;

/**
 * Renders earlier turns of the conversation for prompts: the question, the SQL that answered it,
 * and the start of the conclusion. The SQL is the most useful part for follow-ups ("那华南呢？"),
 * the conclusion is cut short to keep the prompt small.
 */
public final class HistoryText {

	private static final int MAX_CONCLUSION_CHARS = 400;

	private HistoryText() {
	}

	public static String render(List<Turn> history) {
		StringBuilder text = new StringBuilder();
		for (Turn turn : history) {
			text.append("Q: ").append(turn.question()).append('\n');
			if (turn.finalSql() != null) {
				text.append("SQL: ").append(turn.finalSql()).append('\n');
			}
			if (turn.conclusion() != null) {
				String conclusion = turn.conclusion().strip();
				if (conclusion.length() > MAX_CONCLUSION_CHARS) {
					conclusion = conclusion.substring(0, MAX_CONCLUSION_CHARS) + "...";
				}
				text.append("A: ").append(conclusion).append('\n');
			}
			text.append('\n');
		}
		return text.toString().strip();
	}

}
