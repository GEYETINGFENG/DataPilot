package com.datapilot.agent;

import java.util.concurrent.ExecutorService;

/**
 * The agent's thread pools, both bounded.
 * <p>
 * Grouped in one bean rather than exposed as {@code Executor} beans, so they cannot replace
 * Spring Boot's own task executor by accident.
 *
 * @param runs      runs whole analysis turns, sized by {@link AgentProperties#maxConcurrentRuns()}
 * @param drillDown runs drill-down queries in parallel, sized by
 *                  {@link AgentProperties#drillDownParallelism()}
 */
public record AgentExecutors(ExecutorService runs, ExecutorService drillDown) implements AutoCloseable {

	@Override
	public void close() {
		runs.shutdownNow();
		drillDown.shutdownNow();
	}

}
