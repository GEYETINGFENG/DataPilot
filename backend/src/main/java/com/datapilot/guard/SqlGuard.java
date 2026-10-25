package com.datapilot.guard;

import com.datapilot.warehouse.WarehouseProperties;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates model-generated SQL before it reaches the warehouse.
 * <p>
 * This is one layer of defence, not the only one: the warehouse account is read-only and the
 * connection has a statement timeout. The guard exists so that bad SQL is rejected with a message
 * the agent can learn from, instead of a raw database error.
 */
@Component
public class SqlGuard {

	private static final Set<String> SYSTEM_SCHEMAS =
			Set.of("mysql", "information_schema", "performance_schema", "sys");

	// Functions that can stall the database or read the server's filesystem.
	private static final Pattern DANGEROUS_FUNCTIONS =
			Pattern.compile("\\b(sleep|benchmark|load_file|get_lock)\\s*\\(", Pattern.CASE_INSENSITIVE);

	private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'");

	private final int maxRows;

	@Autowired
	public SqlGuard(WarehouseProperties props) {
		this(props.maxRows());
	}

	SqlGuard(int maxRows) {
		this.maxRows = maxRows;
	}

	/**
	 * @return the SQL to execute, with a row limit applied
	 * @throws SqlRejectedException if the statement is not a single safe SELECT
	 */
	public String check(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new SqlRejectedException("SQL is empty");
		}
		Statements statements;
		try {
			statements = CCJSqlParserUtil.parseStatements(sql);
		} catch (JSQLParserException e) {
			throw new SqlRejectedException("SQL could not be parsed: " + rootMessage(e));
		}
		if (statements.size() != 1) {
			throw new SqlRejectedException("Exactly one statement is allowed, got " + statements.size());
		}
		Statement statement = statements.get(0);
		if (!(statement instanceof Select select)) {
			throw new SqlRejectedException("Only SELECT queries are allowed, got "
					+ statement.getClass().getSimpleName().toUpperCase(Locale.ROOT));
		}
		if (select instanceof PlainSelect plain) {
			if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
				throw new SqlRejectedException("SELECT ... INTO is not allowed");
			}
			if (plain.getForMode() != null) {
				throw new SqlRejectedException("Locking reads (FOR UPDATE / FOR SHARE) are not allowed");
			}
		}
		String withoutLiterals = STRING_LITERAL.matcher(sql).replaceAll("''");
		var dangerous = DANGEROUS_FUNCTIONS.matcher(withoutLiterals);
		if (dangerous.find()) {
			throw new SqlRejectedException("Function " + dangerous.group(1).toUpperCase(Locale.ROOT) + " is not allowed");
		}
		for (String table : new TablesNamesFinder<Void>().getTables(statement)) {
			int dot = table.indexOf('.');
			if (dot > 0 && SYSTEM_SCHEMAS.contains(unquote(table.substring(0, dot)).toLowerCase(Locale.ROOT))) {
				throw new SqlRejectedException("System schema access is not allowed: " + table);
			}
		}
		applyRowLimit(select);
		return select.toString();
	}

	private void applyRowLimit(Select select) {
		Limit limit = select.getLimit();
		if (limit == null) {
			select.setLimit(new Limit().withRowCount(new LongValue(maxRows)));
			return;
		}
		Expression rowCount = limit.getRowCount();
		if (!(rowCount instanceof LongValue value) || value.getValue() > maxRows) {
			limit.setRowCount(new LongValue(maxRows));
		}
	}

	private static String unquote(String identifier) {
		return identifier.replace("`", "").replace("\"", "");
	}

	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		// Parser messages list every expected token; the first line is what the model needs.
		return message == null ? cause.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
	}

}
