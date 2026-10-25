package com.datapilot.agent;

import com.datapilot.warehouse.QueryResult;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Outcome of one query step (the main query, or one drill-down), after all its attempts.
 *
 * @param step     1 for the main query, 2.. for drill-downs, as in the {@code sql}/{@code result} events
 * @param title    what the step looks at, e.g. "按大区拆分"; null for the main query
 * @param sql      the statement that produced {@code rows}, or the last one tried if the step failed
 * @param rows     JSON-friendly values only (numbers, strings, booleans, null), see {@link #jsonSafe}
 * @param attempts how many attempts the step took
 * @param error    the last error if every attempt failed, otherwise null
 */
public record StepResult(int step, String title, String sql, List<String> columns, List<List<Object>> rows,
		boolean truncated, long elapsedMs, int attempts, String error) implements Serializable {

	public static StepResult success(int step, String title, String sql, QueryResult result, int attempts) {
		List<List<Object>> rows = new ArrayList<>(result.rows().size());
		for (List<Object> row : result.rows()) {
			List<Object> converted = new ArrayList<>(row.size());
			for (Object value : row) {
				converted.add(jsonSafe(value));
			}
			rows.add(converted);
		}
		return new StepResult(step, title, sql, List.copyOf(result.columns()), rows,
				result.truncated(), result.elapsedMs(), attempts, null);
	}

	public static StepResult failure(int step, String title, String lastSql, int attempts, String error) {
		return new StepResult(step, title, lastSql, List.of(), List.of(), false, 0, attempts, error);
	}

	public boolean succeeded() {
		return error == null;
	}

	/**
	 * A compact text rendering for prompts: the SQL and the first rows as a pipe-separated table.
	 * The model sees a preview; the UI and the charts always get the full result.
	 */
	public String describe(int maxRows) {
		StringBuilder text = new StringBuilder();
		text.append("Step ").append(step);
		if (title != null) {
			text.append(" (").append(title).append(")");
		}
		text.append("\nSQL: ").append(sql == null ? "(none)" : sql).append('\n');
		if (!succeeded()) {
			return text.append("FAILED: ").append(error).append('\n').toString();
		}
		text.append(String.join(" | ", columns)).append('\n');
		rows.stream().limit(maxRows).forEach(row -> text.append(row.stream()
				.map(v -> v == null ? "NULL" : v.toString())
				.collect(Collectors.joining(" | "))).append('\n'));
		if (rows.size() > maxRows) {
			text.append("... ").append(rows.size() - maxRows).append(" more rows\n");
		}
		if (truncated) {
			text.append("(result truncated at the row limit)\n");
		}
		return text.toString();
	}

	/**
	 * JDBC returns driver-specific types ({@code java.sql.Date}, {@code LocalDateTime}, {@code byte[]}...).
	 * Converting them once here keeps the SSE payload, the stored trace and the graph state simple and
	 * serializable: dates become ISO strings, numbers stay numbers.
	 */
	static Object jsonSafe(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Integer || value instanceof Long || value instanceof Short
				|| value instanceof Byte || value instanceof BigDecimal || value instanceof BigInteger) {
			return value;
		}
		if (value instanceof Double d) {
			return d.isNaN() || d.isInfinite() ? null : d;
		}
		if (value instanceof Float f) {
			return f.isNaN() || f.isInfinite() ? null : f.doubleValue();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().toString();
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime().toString();
		}
		if (value instanceof TemporalAccessor temporal) {
			return temporal.toString();
		}
		if (value instanceof byte[] bytes) {
			return "<" + bytes.length + " bytes>";
		}
		return value.toString();
	}

}
