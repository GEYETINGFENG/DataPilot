package com.datapilot.agent.node;

import com.datapilot.agent.AgentModels;
import com.datapilot.agent.AnalysisState;
import com.datapilot.agent.Events;
import com.datapilot.agent.LlmOutput;
import com.datapilot.agent.Prompts;
import com.datapilot.agent.RunContext;
import com.datapilot.agent.StepResult;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns the key results into ECharts options.
 * <p>
 * The model only designs the chart (type, axes, which columns go where, via {@code dataset} +
 * {@code encode}); it never writes the numbers. The data is injected here from the actual query
 * result, so a chart cannot show a value the database did not return. Replies that are not a
 * valid option are dropped: a missing chart is better than a broken one.
 */
@Component
public class ChartNode implements NodeActionWithConfig<AnalysisState> {

	private static final Logger log = LoggerFactory.getLogger(ChartNode.class);

	static final int MAX_CHARTS = 2;
	private static final int PREVIEW_ROWS = 10;
	private static final int MAX_CHART_ROWS = 500;

	private final AgentModels models;
	private final Prompts prompts;
	private final JsonMapper json;

	public ChartNode(AgentModels models, Prompts prompts, JsonMapper json) {
		this.models = models;
		this.prompts = prompts;
		this.json = json;
	}

	@Override
	public Map<String, Object> apply(AnalysisState state, RunnableConfig config) {
		RunContext run = RunContext.of(config);
		// A single number or a single column is better read as text than as a chart.
		List<StepResult> chartable = state.steps().stream()
				.filter(s -> s.succeeded() && s.rows().size() >= 2 && s.columns().size() >= 2)
				.toList();
		if (chartable.isEmpty()) {
			return Map.of();
		}
		run.checkCancelled();
		String reply = AgentModels.ask(models.fast(), prompts.render(Prompts.CHART, Map.of(
				"question", state.question(),
				"results", chartable.stream().map(s -> s.describe(PREVIEW_ROWS)).collect(Collectors.joining("\n")),
				"max_charts", MAX_CHARTS)));
		try {
			for (Events.Chart chart : charts(LlmOutput.parseJson(json, reply), chartable)) {
				run.emit(Events.CHART, chart);
			}
		} catch (IllegalArgumentException e) {
			log.warn("Chart reply not usable: {}", e.getMessage());
		}
		return Map.of();
	}

	List<Events.Chart> charts(JsonNode reply, List<StepResult> steps) {
		List<Events.Chart> charts = new ArrayList<>();
		for (JsonNode item : reply.path("charts").values()) {
			int stepNumber = item.path("step").asInt(-1);
			Optional<StepResult> step = steps.stream().filter(s -> s.step() == stepNumber).findFirst();
			JsonNode option = item.path("option");
			if (step.isEmpty() || !option.isObject() || option.path("series").isMissingNode()) {
				log.warn("Skipping chart for step {}: no such result or no series", stepNumber);
				continue;
			}
			String title = item.path("title").asString(step.get().title() == null ? "" : step.get().title());
			charts.add(new Events.Chart(title, withData((ObjectNode) option.deepCopy(), step.get())));
			if (charts.size() == MAX_CHARTS) {
				break;
			}
		}
		return charts;
	}

	/**
	 * Replaces any data the model wrote with {@code dataset.source} = [header, ...rows].
	 */
	private ObjectNode withData(ObjectNode option, StepResult step) {
		ArrayNode source = json.createArrayNode();
		source.add(json.<JsonNode>valueToTree(step.columns()));
		step.rows().stream().limit(MAX_CHART_ROWS).forEach(row -> source.add(json.<JsonNode>valueToTree(row)));
		option.putObject("dataset").set("source", source);

		JsonNode series = option.get("series");
		if (series.isObject()) {
			series = option.putArray("series").add(series);
		}
		for (JsonNode s : series.values()) {
			if (s instanceof ObjectNode seriesObject) {
				seriesObject.remove("data");
			}
		}
		return option;
	}

}
