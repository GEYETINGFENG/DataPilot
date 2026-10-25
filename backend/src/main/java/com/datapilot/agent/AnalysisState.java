package com.datapilot.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The data that flows through the analysis graph.
 * <p>
 * Each node reads what it needs and returns a partial update. Keys without a channel are simply
 * overwritten; {@link #STEPS} has an appender channel, so the main query and every drill-down
 * round add their results instead of replacing earlier ones.
 */
public class AnalysisState extends AgentState {

	public static final String QUESTION = "question";
	public static final String DATABASE = "database";
	public static final String HINT = "hint";
	public static final String HISTORY = "history";
	public static final String ROUTE = "route";
	public static final String TABLES = "tables";
	public static final String STEPS = "steps";
	public static final String DRILL_DOWN_ROUNDS = "drillDownRounds";
	public static final String DRILL_DOWN_DONE = "drillDownDone";
	public static final String FINAL_SQL = "finalSql";
	public static final String FAILURE = "failure";
	public static final String CONCLUSION = "conclusion";

	public static final Map<String, Channel<?>> SCHEMA = Map.of(
			STEPS, Channels.<StepResult>appender(ArrayList::new));

	public AnalysisState(Map<String, Object> data) {
		super(data);
	}

	public static Map<String, Object> initial(AnalysisInput input) {
		Map<String, Object> data = new HashMap<>();
		data.put(QUESTION, input.question());
		data.put(DATABASE, input.database());
		data.put(HISTORY, input.history());
		if (input.hint() != null && !input.hint().isBlank()) {
			data.put(HINT, input.hint());
		}
		return data;
	}

	public String question() {
		return this.<String>value(QUESTION).orElseThrow();
	}

	public String database() {
		return this.<String>value(DATABASE).orElseThrow();
	}

	public Optional<String> hint() {
		return value(HINT);
	}

	public List<Turn> history() {
		return this.<List<Turn>>value(HISTORY).orElse(List.of());
	}

	public Route route() {
		return this.<Route>value(ROUTE).orElse(Route.SIMPLE);
	}

	public List<String> tables() {
		return this.<List<String>>value(TABLES).orElse(List.of());
	}

	public List<StepResult> steps() {
		return this.<List<StepResult>>value(STEPS).orElse(List.of());
	}

	public Optional<StepResult> mainStep() {
		return steps().stream().filter(s -> s.step() == 1).findFirst();
	}

	public int drillDownRounds() {
		return this.<Integer>value(DRILL_DOWN_ROUNDS).orElse(0);
	}

	public boolean drillDownDone() {
		return this.<Boolean>value(DRILL_DOWN_DONE).orElse(false);
	}

	public Optional<String> finalSql() {
		return value(FINAL_SQL);
	}

	/**
	 * Set when the turn cannot produce an answer (the main query failed every attempt).
	 */
	public Optional<String> failure() {
		return value(FAILURE);
	}

	public Optional<String> conclusion() {
		return value(CONCLUSION);
	}

}
