package com.datapilot.agent;

import com.datapilot.agent.node.ChartNode;
import com.datapilot.agent.node.ConcludeNode;
import com.datapilot.agent.node.DrillDownNode;
import com.datapilot.agent.node.QueryNode;
import com.datapilot.agent.node.RouteNode;
import com.datapilot.agent.node.SelectTablesNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * The analysis workflow as a LangGraph4j state graph:
 *
 * <pre>
 * START → route → select_tables → query ─┬─ failed ───────────────────────→ END
 *                                        ├─ simple ───────────────┐
 *                                        └─ analysis → drill_down ┤ (loops while there is more to explain)
 *                                                                 └→ chart → conclude → END
 * </pre>
 *
 * Nodes emit progress events as they go; this class owns the two terminal events, so every run
 * ends with exactly one {@code done} or {@code error}, whatever happens inside the graph.
 */
@Component
public class AnalysisAgent {

	private static final Logger log = LoggerFactory.getLogger(AnalysisAgent.class);

	static final String ROUTE = "route";
	static final String SELECT_TABLES = "select_tables";
	static final String QUERY = "query";
	static final String DRILL_DOWN = "drill_down";
	static final String CHART = "chart";
	static final String CONCLUDE = "conclude";

	private final CompiledGraph<AnalysisState> graph;

	public AnalysisAgent(RouteNode route, SelectTablesNode selectTables, QueryNode query,
			DrillDownNode drillDown, ChartNode chart, ConcludeNode conclude) throws GraphStateException {
		this.graph = new StateGraph<>(AnalysisState.SCHEMA, AnalysisState::new)
				.addNode(ROUTE, node_async(route))
				.addNode(SELECT_TABLES, node_async(selectTables))
				.addNode(QUERY, node_async(query))
				.addNode(DRILL_DOWN, node_async(drillDown))
				.addNode(CHART, node_async(chart))
				.addNode(CONCLUDE, node_async(conclude))
				.addEdge(START, ROUTE)
				.addEdge(ROUTE, SELECT_TABLES)
				.addEdge(SELECT_TABLES, QUERY)
				.addConditionalEdges(QUERY, edge_async(AnalysisAgent::afterQuery),
						Map.of("failed", END, "simple", CHART, "analysis", DRILL_DOWN))
				.addConditionalEdges(DRILL_DOWN, edge_async(AnalysisAgent::afterDrillDown),
						Map.of("more", DRILL_DOWN, "enough", CHART))
				.addEdge(CHART, CONCLUDE)
				.addEdge(CONCLUDE, END)
				.compile();
	}

	static String afterQuery(AnalysisState state) {
		if (state.failure().isPresent()) {
			return "failed";
		}
		return state.route() == Route.ANALYSIS ? "analysis" : "simple";
	}

	static String afterDrillDown(AnalysisState state) {
		return state.drillDownDone() ? "enough" : "more";
	}

	/**
	 * Runs one turn on the calling thread, reporting to {@code events}. Never throws: failures end
	 * the run with an {@code error} event and a failed outcome.
	 */
	public AnalysisOutcome run(AnalysisInput input, EventSink events) {
		AnalysisOutcome outcome;
		try {
			RunnableConfig config = RunnableConfig.builder()
					.addMetadata(RunContext.KEY, new RunContext(events))
					// Run the nodes on the calling thread (a bounded analysis pool thread) instead of the
					// JVM-wide ForkJoin common pool, which is no place for blocking model and JDBC calls.
					.emitterExecutor(Runnable::run)
					.build();
			AnalysisState state = graph.invoke(AnalysisState.initial(input), config)
					.orElseThrow(() -> new IllegalStateException("The analysis graph produced no state"));
			outcome = state.failure()
					.map(AnalysisOutcome::failed)
					.orElseGet(() -> AnalysisOutcome.done(state.conclusion().orElse(""), state.finalSql().orElse(null)));
		} catch (Exception e) {
			outcome = AnalysisOutcome.failed(describe(e));
		}
		if (outcome.succeeded()) {
			events.emit(Events.DONE, new Events.Done(outcome.finalSql(), outcome.conclusion()));
		} else {
			events.emit(Events.ERROR, new Events.Error(outcome.error()));
		}
		return outcome;
	}

	/**
	 * The graph wraps node exceptions (sometimes twice); report the cause, not the wrapper.
	 */
	private static String describe(Exception e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			if (cause instanceof RunCancelledException) {
				break;
			}
			cause = cause.getCause();
		}
		if (cause instanceof RunCancelledException) {
			return "Analysis cancelled: " + cause.getMessage();
		}
		log.warn("Analysis failed", e);
		String message = cause.getMessage();
		return "Analysis failed: " + (message == null ? cause.getClass().getSimpleName() : message);
	}

}
