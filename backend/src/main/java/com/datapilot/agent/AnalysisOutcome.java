package com.datapilot.agent;

/**
 * How a turn ended. Exactly one of {@code conclusion} / {@code error} is set.
 */
public record AnalysisOutcome(String conclusion, String finalSql, String error) {

	public static AnalysisOutcome done(String conclusion, String finalSql) {
		return new AnalysisOutcome(conclusion, finalSql, null);
	}

	public static AnalysisOutcome failed(String error) {
		return new AnalysisOutcome(null, null, error);
	}

	public boolean succeeded() {
		return error == null;
	}

}
