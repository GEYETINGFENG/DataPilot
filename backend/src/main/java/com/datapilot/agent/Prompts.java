package com.datapilot.agent;

import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt templates live in {@code src/main/resources/prompts/*.txt}, so they can be read and
 * reviewed as text and changed without touching code. Placeholders use {@code {{name}}};
 * {@code {{current_date}}} is filled in automatically.
 */
@Component
public class Prompts {

	public static final String ROUTE = "route";
	public static final String SELECT_TABLES = "select-tables";
	public static final String GENERATE_SQL = "generate-sql";
	public static final String DRILL_DOWN = "drill-down";
	public static final String CHART = "chart";
	public static final String CONCLUDE = "conclude";

	private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

	/**
	 * @param variables null or blank values are rendered as "(none)", so a template never shows an
	 *                  empty section the model might misread
	 */
	public String render(String name, Map<String, ?> variables) {
		Map<String, Object> values = new HashMap<>();
		variables.forEach((key, value) ->
				values.put(key, value == null || value.toString().isBlank() ? "(none)" : value));
		return templates.computeIfAbsent(name, Prompts::load).apply(values).text();
	}

	private static PromptTemplate load(String name) {
		var resource = new ClassPathResource("prompts/" + name + ".txt");
		try {
			return PromptTemplate.from(resource.getContentAsString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Prompt template not found: " + name, e);
		}
	}

}
