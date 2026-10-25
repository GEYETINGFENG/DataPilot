package com.datapilot.agent;

import com.datapilot.guard.SqlGuard;
import com.datapilot.guard.SqlRejectedException;
import com.datapilot.warehouse.QueryResult;
import com.datapilot.warehouse.SchemaCatalog;
import com.datapilot.warehouse.TableSchema;
import com.datapilot.warehouse.WarehouseClient;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One query step with self-correction: generate SQL → guard → execute, and on a guard rejection
 * or database error feed the error back to the model and try again, up to
 * {@link AgentProperties#maxAttempts()} attempts.
 * <p>
 * The main query and every drill-down use this same loop, so they all get the same safety checks
 * and the same {@code sql} / {@code sql_error} / {@code result} events.
 */
@Component
public class SqlStepRunner {

	private final AgentModels models;
	private final Prompts prompts;
	private final SqlGuard guard;
	private final WarehouseClient warehouse;
	private final SchemaCatalog catalog;
	private final AgentProperties props;

	public SqlStepRunner(AgentModels models, Prompts prompts, SqlGuard guard, WarehouseClient warehouse,
			SchemaCatalog catalog, AgentProperties props) {
		this.models = models;
		this.prompts = prompts;
		this.guard = guard;
		this.warehouse = warehouse;
		this.catalog = catalog;
		this.props = props;
	}

	/**
	 * What one step should find out.
	 *
	 * @param step     step number used in events
	 * @param title    short label for drill-down steps, null for the main query
	 * @param question what this query must answer
	 * @param context  background the model needs, e.g. the earlier finding a drill-down breaks down
	 * @param tables   tables whose DDL goes into the prompt
	 */
	public record StepSpec(int step, String title, String question, String context, List<String> tables) {
	}

	public StepResult run(StepSpec spec, AnalysisState state, RunContext run) {
		ChatModel model = models.forRoute(state.route());
		String schemaDdl = SchemaText.ddl(selectTables(state.database(), spec.tables()));
		List<String> failures = new ArrayList<>();
		String lastSql = null;
		String lastError = null;

		for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
			run.checkCancelled();
			String prompt = prompts.render(Prompts.GENERATE_SQL, Map.of(
					"database", state.database(),
					"schema", schemaDdl,
					"hint", state.hint().orElse(""),
					"history", HistoryText.render(state.history()),
					"context", spec.context() == null ? "" : spec.context(),
					"question", spec.question(),
					"previous_attempts", String.join("\n", failures)));
			String candidate = LlmOutput.extractSql(AgentModels.ask(model, prompt));
			lastSql = candidate;

			String sql;
			try {
				sql = guard.check(candidate);
			} catch (SqlRejectedException e) {
				// The rejected SQL never runs, but the UI still shows what the model wrote.
				run.emit(Events.SQL, new Events.Sql(spec.step(), attempt, candidate));
				lastError = "Rejected by the SQL guard: " + e.getMessage();
				run.emit(Events.SQL_ERROR, new Events.SqlError(spec.step(), attempt, lastError));
				failures.add(feedback(attempt, candidate, lastError));
				continue;
			}

			run.emit(Events.SQL, new Events.Sql(spec.step(), attempt, sql));
			run.checkCancelled();
			try {
				QueryResult result = warehouse.query(state.database(), sql);
				StepResult step = StepResult.success(spec.step(), spec.title(), sql, result, attempt);
				run.emit(Events.RESULT, new Events.Result(spec.step(), attempt, step.columns(), step.rows(),
						step.truncated(), step.elapsedMs()));
				return step;
			} catch (DataAccessException e) {
				lastSql = sql;
				lastError = e.getMostSpecificCause().getMessage();
				run.emit(Events.SQL_ERROR, new Events.SqlError(spec.step(), attempt, lastError));
				failures.add(feedback(attempt, sql, lastError));
			}
		}
		return StepResult.failure(spec.step(), spec.title(), lastSql, props.maxAttempts(), lastError);
	}

	/**
	 * The selected tables in catalog order; unknown names are dropped. Falls back to every table
	 * if nothing matches, so the model at least sees a schema.
	 */
	private List<TableSchema> selectTables(String database, List<String> names) {
		List<TableSchema> all = catalog.tables(database);
		Set<String> wanted = names.stream().map(String::toLowerCase).collect(Collectors.toSet());
		List<TableSchema> selected = all.stream()
				.filter(t -> wanted.contains(t.name().toLowerCase()))
				.toList();
		return selected.isEmpty() ? all : selected;
	}

	private static String feedback(int attempt, String sql, String error) {
		return "Attempt " + attempt + ":\n```sql\n" + sql + "\n```\nError: " + error + "\n";
	}

}
