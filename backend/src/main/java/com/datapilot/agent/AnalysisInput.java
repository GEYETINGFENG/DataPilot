package com.datapilot.agent;

import java.util.List;

/**
 * Everything one turn starts from.
 *
 * @param database the schema to analyse, already checked against the allowlist
 * @param hint     extra domain knowledge, may be null
 * @param history  earlier turns of the conversation, oldest first
 */
public record AnalysisInput(String question, String database, String hint, List<Turn> history) {

	public AnalysisInput {
		history = history == null ? List.of() : List.copyOf(history);
	}

}
