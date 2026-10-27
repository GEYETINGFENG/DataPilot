package com.datapilot.agent.node;

import com.datapilot.agent.AgentModels;
import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.Events;
import com.datapilot.agent.HistoryText;
import com.datapilot.agent.LlmOutput;
import com.datapilot.agent.Prompts;
import com.datapilot.agent.Route;
import com.datapilot.agent.RunContext;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Classifies the question so simple lookups take the short, cheap path.
 * <p>
 * Uses the fast model: this is a one-word decision, and it runs on every question.
 * If the reply cannot be parsed the question is treated as {@link Route#SIMPLE}: a router failure
 * should never be the reason a turn becomes expensive.
 */
@Component
public class RouteNode implements NodeActionWithConfig<AnalysisState> {

	private static final Logger log = LoggerFactory.getLogger(RouteNode.class);

	private final AgentModels models;
	private final Prompts prompts;
	private final JsonMapper json;

	public RouteNode(AgentModels models, Prompts prompts, JsonMapper json) {
		this.models = models;
		this.prompts = prompts;
		this.json = json;
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) {
		RunContext run = RunContext.of(config);
		run.checkCancelled();
		String reply = AgentModels.ask(models.fast(), prompts.render(Prompts.ROUTE, Map.of(
				"question", state.question(),
				"history", HistoryText.render(state.history()))));

		Route route = Route.SIMPLE;
		String plan = null;
		try {
			JsonNode node = LlmOutput.parseJson(json, reply);
			route = "analysis".equalsIgnoreCase(node.path("route").asString("")) ? Route.ANALYSIS : Route.SIMPLE;
			plan = node.path("plan").asString(null);
		} catch (IllegalArgumentException e) {
			log.warn("Router reply not understood, treating the question as simple: {}", e.getMessage());
		}
		if (plan != null && !plan.isBlank()) {
			run.emit(Events.PLAN, new Events.Plan(plan));
		}
		return Map.of(AnalysisState.ROUTE, route);
	}

}
