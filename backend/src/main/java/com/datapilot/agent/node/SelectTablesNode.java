package com.datapilot.agent.node;

import com.datapilot.agent.AgentModels;
import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.Events;
import com.datapilot.agent.HistoryText;
import com.datapilot.agent.LlmOutput;
import com.datapilot.agent.Prompts;
import com.datapilot.agent.RunContext;
import com.datapilot.agent.SchemaText;
import com.datapilot.warehouse.SchemaCatalog;
import com.datapilot.warehouse.TableSchema;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Picks the tables the question needs, from table and column names and comments.
 * <p>
 * Only the selected tables' DDL goes into the SQL prompt. That keeps prompts small for wide
 * schemas and, more importantly, gives the model fewer wrong tables to confuse.
 */
@Component
public class SelectTablesNode implements NodeActionWithConfig<AnalysisState> {

	private static final Logger log = LoggerFactory.getLogger(SelectTablesNode.class);

	private final AgentModels models;
	private final Prompts prompts;
	private final SchemaCatalog catalog;
	private final JsonMapper json;

	public SelectTablesNode(AgentModels models, Prompts prompts, SchemaCatalog catalog, JsonMapper json) {
		this.models = models;
		this.prompts = prompts;
		this.catalog = catalog;
		this.json = json;
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) {
		RunContext run = RunContext.of(config);
		List<TableSchema> all = catalog.tables(state.database());
		if (all.isEmpty()) {
			throw new IllegalStateException("Database '" + state.database() + "' has no tables the agent can read");
		}
		run.checkCancelled();
		String reply = AgentModels.ask(models.fast(), prompts.render(Prompts.SELECT_TABLES, Map.of(
				"database", state.database(),
				"catalog", SchemaText.catalog(all),
				"hint", state.hint().orElse(""),
				"history", HistoryText.render(state.history()),
				"question", state.question())));

		List<String> selected = List.of();
		try {
			selected = known(all, LlmOutput.parseJson(json, reply).path("tables"));
		} catch (IllegalArgumentException e) {
			log.warn("Table selection reply not understood: {}", e.getMessage());
		}
		if (selected.isEmpty()) {
			// Better a long prompt than no answer.
			selected = all.stream().map(TableSchema::name).toList();
		}
		run.emit(Events.TABLES, new Events.Tables(selected));
		return Map.of(AnalysisState.TABLES, selected);
	}

	/**
	 * Keeps only names that exist, in their real spelling. Models sometimes invent a plausible table.
	 */
	static List<String> known(List<TableSchema> all, JsonNode names) {
		if (!names.isArray()) {
			return List.of();
		}
		return all.stream()
				.map(TableSchema::name)
				.filter(table -> names.values().stream().anyMatch(n -> table.equalsIgnoreCase(n.asString(""))))
				.toList();
	}

}
