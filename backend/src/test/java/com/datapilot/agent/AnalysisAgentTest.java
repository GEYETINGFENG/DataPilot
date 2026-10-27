package com.datapilot.agent;

import com.datapilot.warehouse.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import java.math.BigDecimal;
import java.sql.SQLSyntaxErrorException;
import java.util.List;

import static com.datapilot.agent.ScriptedChatModel.CHART;
import static com.datapilot.agent.ScriptedChatModel.CONCLUDE;
import static com.datapilot.agent.ScriptedChatModel.DRILL_DOWN;
import static com.datapilot.agent.ScriptedChatModel.ROUTE;
import static com.datapilot.agent.ScriptedChatModel.SELECT_TABLES;
import static com.datapilot.agent.ScriptedChatModel.SQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisAgentTest {

	private static final String SIMPLE_ROUTE = """
			{"route": "simple", "plan": "统计订单数"}""";
	private static final String ORDERS_ONLY = """
			{"tables": ["orders"]}""";
	private static final String GOOD_SQL = """
			```sql
			SELECT COUNT(*) AS orders FROM orders
			```""";
	private static final String BAD_COLUMN_SQL = """
			```sql
			SELECT COUNT(bad_col) AS orders FROM orders
			```""";

	private final AgentKit kit = new AgentKit();
	private final RecordingSink sink = new RecordingSink();

	AnalysisAgentTest() throws Exception {
	}

	@AfterEach
	void close() {
		kit.close();
	}

	private static AnalysisInput question(String text) {
		return new AnalysisInput(text, AgentKit.DEFAULT_SCHEMA, null, List.of());
	}

	private void ordersCountIs(long count) {
		when(kit.warehouse.query(eq(AgentKit.DEFAULT_SCHEMA), contains("COUNT(*)")))
				.thenReturn(new QueryResult(List.of("orders"), List.of(List.of(count)), false, 12));
	}

	private void badColumnFails() {
		when(kit.warehouse.query(anyString(), contains("bad_col"))).thenThrow(new BadSqlGrammarException(
				"query", "SELECT ...", new SQLSyntaxErrorException("Unknown column 'bad_col' in 'field list'")));
	}

	@Test
	void simpleQuestionGoesStraightFromQueryToConclusion() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, GOOD_SQL)
				.on(CONCLUDE, "共有 **42** 笔订单。");
		ordersCountIs(42);

		AnalysisOutcome outcome = kit.agent.run(new AnalysisInput("一共有多少订单？", AgentKit.DEFAULT_SCHEMA, null,
				List.of(new Turn("上个月呢？", "SELECT 1 FROM orders", "1 笔"))), sink);

		assertThat(sink.names()).containsExactly("plan", "tables", "sql", "result", "token", "done");
		assertThat(sink.data(Events.TABLES, Events.Tables.class).get(0).tables()).containsExactly("orders");
		Events.Sql sql = sink.data(Events.SQL, Events.Sql.class).get(0);
		assertThat(sql.step()).isEqualTo(1);
		assertThat(sql.attempt()).isEqualTo(1);
		// The guard's rewrite is what runs and what the UI sees.
		assertThat(sql.sql()).isEqualToIgnoringCase("SELECT COUNT(*) AS orders FROM orders LIMIT 1000");
		assertThat(sink.data(Events.RESULT, Events.Result.class).get(0).rows()).containsExactly(List.of(42L));
		assertThat(sink.tokens()).isEqualTo("共有 **42** 笔订单。");

		Events.Done done = sink.data(Events.DONE, Events.Done.class).get(0);
		assertThat(done.finalSql()).isEqualTo(sql.sql());
		assertThat(done.conclusion()).isEqualTo("共有 **42** 笔订单。");
		assertThat(outcome.succeeded()).isTrue();

		// Conversation memory reaches SQL generation, so follow-ups can build on earlier queries.
		assertThat(kit.model.prompts(SQL).get(0)).contains("Q: 上个月呢？").contains("SQL: SELECT 1 FROM orders");
		// One row, one column: nothing to chart, so no model call for it.
		assertThat(kit.model.prompts(CHART)).isEmpty();
		assertThat(kit.model.prompts(DRILL_DOWN)).isEmpty();
	}

	@Test
	void badColumnIsFedBackAndTheRetrySucceeds() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, BAD_COLUMN_SQL, GOOD_SQL)
				.on(CONCLUDE, "42 笔。");
		badColumnFails();
		ordersCountIs(42);

		kit.agent.run(question("一共有多少订单？"), sink);

		assertThat(sink.names()).containsExactly("plan", "tables", "sql", "sql_error", "sql", "result", "token", "done");
		Events.SqlError error = sink.data(Events.SQL_ERROR, Events.SqlError.class).get(0);
		assertThat(error.step()).isEqualTo(1);
		assertThat(error.attempt()).isEqualTo(1);
		assertThat(error.message()).contains("Unknown column 'bad_col'");
		Events.Result result = sink.data(Events.RESULT, Events.Result.class).get(0);
		assertThat(result.attempt()).isEqualTo(2);

		List<String> sqlPrompts = kit.model.prompts(SQL);
		assertThat(sqlPrompts).hasSize(2);
		assertThat(sqlPrompts.get(1)).contains("COUNT(bad_col)").contains("Unknown column 'bad_col' in 'field list'");
		assertThat(sink.data(Events.DONE, Events.Done.class).get(0).finalSql()).doesNotContain("bad_col");
	}

	@Test
	void exhaustingRetriesEndsWithError() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, BAD_COLUMN_SQL)
				.on(CONCLUDE, "should not be called");
		badColumnFails();

		AnalysisOutcome outcome = kit.agent.run(question("一共有多少订单？"), sink);

		assertThat(sink.names()).containsExactly("plan", "tables",
				"sql", "sql_error", "sql", "sql_error", "sql", "sql_error", "error");
		assertThat(sink.data(Events.SQL_ERROR, Events.SqlError.class)).extracting(Events.SqlError::attempt)
				.containsExactly(1, 2, 3);
		assertThat(sink.data(Events.ERROR, Events.Error.class).get(0).message())
				.contains("3 attempts").contains("Unknown column 'bad_col'");
		assertThat(outcome.succeeded()).isFalse();
		assertThat(kit.model.prompts(CONCLUDE)).isEmpty();
	}

	@Test
	void guardRejectionIsFedBackWithoutTouchingTheDatabase() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, "DELETE FROM orders", GOOD_SQL)
				.on(CONCLUDE, "42 笔。");
		ordersCountIs(42);

		kit.agent.run(question("一共有多少订单？"), sink);

		assertThat(sink.names()).containsExactly("plan", "tables", "sql", "sql_error", "sql", "result", "token", "done");
		assertThat(sink.data(Events.SQL, Events.Sql.class).get(0).sql()).isEqualTo("DELETE FROM orders");
		assertThat(sink.data(Events.SQL_ERROR, Events.SqlError.class).get(0).message())
				.startsWith("Rejected by the SQL guard").contains("Only SELECT");
		verify(kit.warehouse, never()).query(anyString(), contains("DELETE"));
		assertThat(kit.model.prompts(SQL).get(1)).contains("Only SELECT");
	}

	@Test
	void analysisQuestionDrillsDownInParallelAndCharts() {
		kit.model.on(ROUTE, """
						{"route": "analysis", "plan": "先找下降最多的月份，再按维度拆分"}""")
				.on(SELECT_TABLES, """
						{"tables": ["orders", "users", "made_up_table"]}""")
				.on(SQL, """
						SELECT order_date AS month, COUNT(*) AS orders FROM orders GROUP BY 1""")
				.on(DRILL_DOWN, """
						{"done": false, "plan": "按大区和用户拆分", "dimensions": [
						  {"title": "按大区", "question": "orders by region", "tables": ["orders", "users"]},
						  {"title": "按用户", "question": "orders by user", "tables": ["orders"]}]}""",
						"""
						{"done": true}""")
				.on(CHART, """
						{"charts": [{"step": 1, "title": "订单趋势", "option": {
						  "xAxis": {"type": "category"}, "yAxis": {"type": "value"},
						  "series": {"type": "line", "data": [1, 2, 3], "encode": {"x": "month", "y": "orders"}}}},
						 {"step": 99, "title": "no such step", "option": {"series": []}}]}""")
				.on(CONCLUDE, "订单在 8 月下降。");
		when(kit.warehouse.query(eq(AgentKit.DEFAULT_SCHEMA), anyString())).thenReturn(new QueryResult(
				List.of("month", "orders"),
				List.of(List.of(java.sql.Date.valueOf("2026-07-01"), new BigDecimal("10")),
						List.of(java.sql.Date.valueOf("2026-08-01"), new BigDecimal("7"))),
				false, 5));

		kit.agent.run(question("为什么 8 月订单下降？"), sink);

		// Unknown tables from the model are dropped.
		assertThat(sink.data(Events.TABLES, Events.Tables.class).get(0).tables()).containsExactly("orders", "users");
		assertThat(sink.data(Events.RESULT, Events.Result.class)).extracting(Events.Result::step)
				.containsExactlyInAnyOrder(1, 2, 3);
		// JDBC dates become ISO strings, numbers stay numbers.
		assertThat(sink.data(Events.RESULT, Events.Result.class).get(0).rows().get(0))
				.containsExactly("2026-07-01", new BigDecimal("10"));
		assertThat(sink.data(Events.PLAN, Events.Plan.class)).extracting(Events.Plan::text)
				.containsExactly("先找下降最多的月份，再按维度拆分", "按大区和用户拆分");
		// The second planning round sees the drill-down results.
		assertThat(kit.model.prompts(DRILL_DOWN)).hasSize(2);
		assertThat(kit.model.prompts(DRILL_DOWN).get(1)).contains("Step 2 (按大区)").contains("Step 3 (按用户)");

		List<Events.Chart> charts = sink.data(Events.CHART, Events.Chart.class);
		assertThat(charts).hasSize(1);
		Events.Chart chart = charts.get(0);
		assertThat(chart.title()).isEqualTo("订单趋势");
		// The data comes from the query result, never from the model.
		assertThat(chart.option().path("dataset").path("source").toString())
				.isEqualTo("[[\"month\",\"orders\"],[\"2026-07-01\",10],[\"2026-08-01\",7]]");
		assertThat(chart.option().path("series").isArray()).isTrue();
		assertThat(chart.option().path("series").get(0).has("data")).isFalse();

		List<String> names = sink.names();
		assertThat(names.get(names.size() - 1)).isEqualTo("done");
		assertThat(names.indexOf("chart")).isLessThan(names.indexOf("token"));
		// finalSql is the main query, not a drill-down.
		assertThat(sink.data(Events.DONE, Events.Done.class).get(0).finalSql())
				.isEqualTo(sink.data(Events.SQL, Events.Sql.class).stream().filter(s -> s.step() == 1)
						.findFirst().orElseThrow().sql());
	}

	@Test
	void drillDownStopsAtTheStepBudget() {
		kit.model.on(ROUTE, """
						{"route": "analysis"}""")
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, GOOD_SQL)
				.on(DRILL_DOWN, """
						{"done": false, "dimensions": [
						  {"title": "a", "question": "a"}, {"title": "b", "question": "b"},
						  {"title": "c", "question": "c"}, {"title": "d", "question": "d"}]}""")
				.on(CONCLUDE, "done");
		ordersCountIs(1);

		kit.agent.run(question("why?"), sink);

		// 1 main query + at most 3 per round; the second round only gets the 2 steps left of 6.
		assertThat(sink.data(Events.RESULT, Events.Result.class)).extracting(Events.Result::step)
				.containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
		assertThat(kit.model.prompts(DRILL_DOWN)).hasSize(2);
		assertThat(kit.model.prompts(DRILL_DOWN).get(1)).contains("at most 2 breakdowns");
	}

	@Test
	void nonDefaultDatabaseIsUsedForSchemaAndQueries() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, GOOD_SQL)
				.on(CONCLUDE, "ok");
		when(kit.warehouse.query(eq(AgentKit.OTHER_SCHEMA), anyString()))
				.thenReturn(new QueryResult(List.of("orders"), List.of(List.of(1L)), false, 1));

		kit.agent.run(new AnalysisInput("how many orders?", AgentKit.OTHER_SCHEMA, "orders means paid orders",
				List.of()), sink);

		assertThat(sink.names()).endsWith("done");
		verify(kit.warehouse).listTables(AgentKit.OTHER_SCHEMA);
		verify(kit.warehouse).query(eq(AgentKit.OTHER_SCHEMA), anyString());
		verify(kit.warehouse, never()).query(eq(AgentKit.DEFAULT_SCHEMA), anyString());
		assertThat(kit.model.prompts(SQL).get(0)).contains("`bird_db`").contains("orders means paid orders");
	}

	@Test
	void clientDisconnectStopsTheRunAndEndsWithError() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY)
				.on(SQL, GOOD_SQL)
				.on(CONCLUDE, "should not be called");
		ordersCountIs(42);
		RecordingSink leaving = new RecordingSink().closeAfter(Events.TABLES);

		AnalysisOutcome outcome = kit.agent.run(question("一共有多少订单？"), leaving);

		assertThat(leaving.names()).containsExactly("plan", "tables", "error");
		assertThat(outcome.error()).startsWith("Analysis cancelled");
		assertThat(kit.model.prompts(SQL)).isEmpty();
	}

	@Test
	void modelFailureEndsWithError() {
		kit.model.on(ROUTE, SIMPLE_ROUTE)
				.on(SELECT_TABLES, ORDERS_ONLY);
		// No script for SQL generation: the fake throws, like a model API that is down.

		AnalysisOutcome outcome = kit.agent.run(question("一共有多少订单？"), sink);

		assertThat(sink.names()).containsExactly("plan", "tables", "error");
		assertThat(outcome.succeeded()).isFalse();
	}

}
