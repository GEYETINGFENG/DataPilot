package com.datapilot.agent.node;

import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.SqlStepRunner;
import com.datapilot.agent.SqlStepRunner.StepSpec;
import com.datapilot.agent.RunContext;
import com.datapilot.agent.StepResult;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 1: the query that answers the question itself, with self-correction.
 * <p>
 * Its SQL becomes {@code finalSql} in the {@code done} event. Drill-downs explain the answer but
 * never replace it, so evaluation always scores this query.
 */
@Component
public class QueryNode implements NodeActionWithConfig<AnalysisState> {

	private final SqlStepRunner runner;

	public QueryNode(SqlStepRunner runner) {
		this.runner = runner;
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) {
		StepResult result = runner.run(new StepSpec(1, null, state.question(), null, state.tables()),
				state, RunContext.of(config));
		Map<String, Object> update = new HashMap<>();
		update.put(AnalysisState.STEPS, List.of(result));
		if (result.succeeded()) {
			update.put(AnalysisState.FINAL_SQL, result.sql());
		} else {
			update.put(AnalysisState.FAILURE, "Could not write a working query after " + result.attempts()
					+ " attempts. Last error: " + result.error());
		}
		return update;
	}

}
