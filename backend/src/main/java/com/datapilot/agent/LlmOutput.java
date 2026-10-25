package com.datapilot.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured content from model replies.
 * <p>
 * Prompts ask for a fenced block or bare JSON, but models still add prose around it now and then.
 * Parsing tolerantly here, and validating the result in the caller, is cheaper than a retry.
 */
public final class LlmOutput {

	private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\s*\\n?(.*?)```", Pattern.DOTALL);

	private LlmOutput() {
	}

	/**
	 * @return the SQL inside the first fenced block, or the whole reply if there is none
	 */
	public static String extractSql(String reply) {
		if (reply == null) {
			return "";
		}
		Matcher fence = FENCE.matcher(reply);
		String sql = fence.find() ? fence.group(1) : reply;
		return sql.strip();
	}

	/**
	 * @throws IllegalArgumentException if the reply holds no JSON object or array
	 */
	public static JsonNode parseJson(JsonMapper json, String reply) {
		if (reply == null) {
			throw new IllegalArgumentException("Empty reply");
		}
		Matcher fence = FENCE.matcher(reply);
		String text = fence.find() ? fence.group(1) : reply;
		int start = firstIndexOf(text, '{', '[');
		int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
		if (start < 0 || end < start) {
			throw new IllegalArgumentException("No JSON in reply: " + abbreviate(reply));
		}
		try {
			return json.readTree(text.substring(start, end + 1));
		} catch (JacksonException e) {
			throw new IllegalArgumentException("Invalid JSON in reply: " + e.getOriginalMessage());
		}
	}

	private static int firstIndexOf(String text, char a, char b) {
		int i = text.indexOf(a);
		int j = text.indexOf(b);
		if (i < 0) {
			return j;
		}
		return j < 0 ? i : Math.min(i, j);
	}

	static String abbreviate(String text) {
		return text.length() <= 200 ? text : text.substring(0, 200) + "...";
	}

}
