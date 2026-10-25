package com.datapilot.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limits that bound the cost and duration of one analysis turn.
 *
 * @param maxAttempts             SQL attempts per step before giving up (first try + corrections)
 * @param maxSteps                queries per turn, the main query plus every drill-down
 * @param maxDrillDownDimensions  breakdowns the model may propose in one drill-down round
 * @param maxDrillDownRounds      how many times the agent may drill further into a finding
 * @param drillDownParallelism    drill-down queries running at once, across all turns. Keep it at or
 *                                below the warehouse connection pool size
 * @param maxConcurrentRuns       analysis turns running at once; further requests get 503
 * @param historyTurns            earlier question/answer pairs given to the model as context
 * @param previewRows             result rows shown to the model; the UI always gets all of them
 * @param streamTimeout           the SSE stream is closed with an {@code error} event after this
 */
@ConfigurationProperties("datapilot.agent")
public record AgentProperties(
		@DefaultValue("3") int maxAttempts,
		@DefaultValue("6") int maxSteps,
		@DefaultValue("3") int maxDrillDownDimensions,
		@DefaultValue("2") int maxDrillDownRounds,
		@DefaultValue("4") int drillDownParallelism,
		@DefaultValue("8") int maxConcurrentRuns,
		@DefaultValue("3") int historyTurns,
		@DefaultValue("30") int previewRows,
		@DefaultValue("5m") Duration streamTimeout) {

	/**
	 * Defaults, for tests and for code that runs outside Spring.
	 */
	public static AgentProperties defaults() {
		return new AgentProperties(3, 6, 3, 2, 4, 8, 3, 30, Duration.ofMinutes(5));
	}

}
