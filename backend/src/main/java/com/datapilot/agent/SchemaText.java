package com.datapilot.agent;

import com.datapilot.warehouse.TableSchema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders table metadata for prompts.
 * <p>
 * Two levels of detail: a one-line-per-table catalog for choosing tables (cheap even for large
 * schemas), and DDL with column comments for writing SQL (only for the selected tables). The
 * comments matter as much as the names: they carry units, enum values and join keys.
 */
public final class SchemaText {

	private SchemaText() {
	}

	public static String catalog(List<TableSchema> tables) {
		return tables.stream()
				.map(t -> "- " + t.name() + comment(" — ", t.comment()) + "\n  columns: "
						+ t.columns().stream()
						.map(c -> c.name() + comment(" (", c.comment(), ")"))
						.collect(Collectors.joining(", ")))
				.collect(Collectors.joining("\n"));
	}

	public static String ddl(List<TableSchema> tables) {
		StringBuilder text = new StringBuilder();
		for (TableSchema table : tables) {
			text.append("CREATE TABLE ").append(table.name()).append(" (")
					.append(comment(" -- ", table.comment())).append('\n');
			for (int i = 0; i < table.columns().size(); i++) {
				TableSchema.Column column = table.columns().get(i);
				text.append("  ").append(column.name()).append(' ').append(column.type());
				if (column.primaryKey()) {
					text.append(" PRIMARY KEY");
				}
				if (i < table.columns().size() - 1) {
					text.append(',');
				}
				text.append(comment(" -- ", column.comment())).append('\n');
			}
			text.append(");\n");
		}
		return text.toString();
	}

	private static String comment(String prefix, String comment) {
		return comment(prefix, comment, "");
	}

	private static String comment(String prefix, String comment, String suffix) {
		return comment == null || comment.isBlank() ? "" : prefix + comment.strip() + suffix;
	}

}
