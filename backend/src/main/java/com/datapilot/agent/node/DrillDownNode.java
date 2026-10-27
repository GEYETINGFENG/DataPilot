package com.datapilot.agent.node;

import com.datapilot.agent.AgentExecutors;
import com.datapilot.agent.AgentModels;
import com.datapilot.agent.AgentProperties;
import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.Events;
import com.datapilot.agent.LlmOutput;
import com.datapilot.agent.Prompts;
import com.datapilot.agent.RunCancelledException;
import com.datapilot.agent.RunContext;
import com.datapilot.agent.SchemaText;
import com.datapilot.agent.SqlStepRunner;
import com.datapilot.agent.SqlStepRunner.StepSpec;
import com.datapilot.agent.StepResult;
import com.datapilot.warehouse.SchemaCatalog;
import com.datapilot.warehouse.TableSchema;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * One drill-down round for analysis questions.
 * <p>
 * The reasoning model looks at what has been found so far and proposes up to
 * {@link AgentProperties#maxDrillDownDimensions()} breakdowns ("split the drop by region",
 * "compare refund reasons"), or says the finding is explained. The proposed queries are
 * independent of each other, so they run in parallel on a bounded pool. The graph loops back
 * here until the model is satisfied, {@link AgentProperties#maxDrillDownRounds()} rounds have
 * run, or the turn has used {@link AgentProperties#maxSteps()} queries.
 */
@Component
public class DrillDownNode implements NodeActionWithConfig<AnalysisState> {

	private static final Logger log = LoggerFactory.getLogger(DrillDownNode.class);

	private final AgentModels models;
	private final Prompts prompts;
	private final SchemaCatalog catalog;
	private final SqlStepRunner runner;
	private final AgentExecutors executors;
	private final AgentProperties props;
	private final JsonMapper json;

	public DrillDownNode(AgentModels models, Prompts prompts, SchemaCatalog catalog, SqlStepRunner runner,
			AgentExecutors executors, AgentProperties props, JsonMapper json) {
		this.models = models;
		this.prompts = prompts;
		this.catalog = catalog;
		this.runner = runner;
		this.executors = executors;
		this.props = props;
		this.json = json;
	}

	record Dimension(String title, String question, List<String> tables) {
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) {
		RunContext run = RunContext.of(config);
		int round = state.drillDownRounds() + 1;
		int stepsUsed = state.steps().size();
		int budget = Math.min(props.maxDrillDownDimensions(), props.maxSteps() - stepsUsed);
		if (budget <= 0) {
			return finished(round);
		}

		List<TableSchema> all = catalog.tables(state.database());
		run.checkCancelled();
		String reply = AgentModels.ask(models.reasoning(), prompts.render(Prompts.DRILL_DOWN, Map.of(
				"question", state.question(),
				"hint", state.hint().orElse(""),
				"catalog", SchemaText.catalog(all),
				"findings", Findings.describe(state.steps(), props.previewRows()),
				"max_dimensions", budget)));

		List<Dimension> dimensions;
		String plan;
		try {
			JsonNode node = LlmOutput.parseJson(json, reply);
			if (node.path("done").asBoolean(false)) {
				return finished(round);
			}
			dimensions = dimensions(node.path("dimensions"), all, state.tables(), budget);
			plan = node.path("plan").asString(null);
		} catch (IllegalArgumentException e) {
			// Drill-down is optional: without it the turn still has its main answer.
			log.warn("Drill-down plan not understood, skipping: {}", e.getMessage());
			return finished(round);
		}
		if (dimensions.isEmpty()) {
			return finished(round);
		}

		if (plan == null || plan.isBlank()) {
			plan = dimensions.stream().map(Dimension::title).collect(Collectors.joining("、"));
		}
		run.emit(Events.PLAN, new Events.Plan(plan));
		Set<String> tables = new LinkedHashSet<>();
		dimensions.forEach(d -> tables.addAll(d.tables()));
		run.emit(Events.TABLES, new Events.Tables(List.copyOf(tables)));

		String context = "Overall question: " + state.question() + "\n\nFindings so far:\n"
				+ Findings.describe(state.steps(), props.previewRows());
		List<CompletableFuture<StepResult>> futures = new ArrayList<>();
		for (int i = 0; i < dimensions.size(); i++) {
			Dimension dimension = dimensions.get(i);
			StepSpec spec = new StepSpec(stepsUsed + i + 1, dimension.title(), dimension.question(), context,
					dimension.tables());
			futures.add(CompletableFuture.supplyAsync(() -> runStep(spec, state, run), executors.drillDown()));
		}
		List<StepResult> results = new ArrayList<>();
		try {
			for (CompletableFuture<StepResult> future : futures) {
				results.add(future.join());
			}
		} catch (CompletionException e) {
			// Only cancellation gets here (see runStep); the other steps stop at their next check.
			throw e.getCause() instanceof RuntimeException cause ? cause : e;
		}

		boolean done = round >= props.maxDrillDownRounds() || stepsUsed + results.size() >= props.maxSteps();
		return Map.of(
				AnalysisState.STEPS, results,
				AnalysisState.DRILL_DOWN_ROUNDS, round,
				AnalysisState.DRILL_DOWN_DONE, done);
	}

	/**
	 * A drill-down that fails unexpectedly (say, the model call times out) is recorded as a failed
	 * step instead of failing the turn: the main answer is still worth delivering.
	 */
	private StepResult runStep(StepSpec spec, AnalysisState state, RunContext run) {
		try {
			return runner.run(spec, state, run);
		} catch (RunCancelledException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("Drill-down step {} failed", spec.step(), e);
			return StepResult.failure(spec.step(), spec.title(), null, 0, e.getMessage());
		}
	}

	private static Map<String, Object> finished(int round) {
		return Map.of(AnalysisState.DRILL_DOWN_ROUNDS, round, AnalysisState.DRILL_DOWN_DONE, true);
	}

	/**
	 * Validates the proposed breakdowns: each needs a question, tables are limited to ones that
	 * exist (falling back to the main query's tables), and at most {@code budget} are kept.
	 */
	static List<Dimension> dimensions(JsonNode proposed, List<TableSchema> all, List<String> defaultTables,
			int budget) {
		List<Dimension> dimensions = new ArrayList<>();
		if (!proposed.isArray()) {
			return dimensions;
		}
		for (JsonNode item : proposed.values()) {
			String question = item.path("question").asString("").strip();
			if (question.isEmpty()) {
				continue;
			}
			String title = item.path("title").asString("").strip();
			List<String> tables = SelectTablesNode.known(all, item.path("tables"));
			dimensions.add(new Dimension(title.isEmpty() ? question : title, question,
					tables.isEmpty() ? defaultTables : tables));
			if (dimensions.size() == budget) {
				break;
			}
		}
		return dimensions;
	}

}
