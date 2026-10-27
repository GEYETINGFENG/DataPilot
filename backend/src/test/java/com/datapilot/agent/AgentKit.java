package com.datapilot.agent;

import com.datapilot.agent.node.ChartNode;
import com.datapilot.agent.node.ConcludeNode;
import com.datapilot.agent.node.DrillDownNode;
import com.datapilot.agent.node.QueryNode;
import com.datapilot.agent.node.RouteNode;
import com.datapilot.agent.node.SelectTablesNode;
import com.datapilot.guard.SqlGuard;
import com.datapilot.warehouse.SchemaCatalog;
import com.datapilot.warehouse.TableSchema;
import com.datapilot.warehouse.WarehouseClient;
import com.datapilot.warehouse.WarehouseProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wires the real graph, nodes, prompts and SQL guard to a scripted model and a mocked warehouse.
 */
class AgentKit implements AutoCloseable {

	static final String DEFAULT_SCHEMA = "demo_shop";
	static final String OTHER_SCHEMA = "bird_db";

	static final List<TableSchema> TABLES = List.of(
			new TableSchema("orders", "订单", List.of(
					new TableSchema.Column("id", "int", true, null),
					new TableSchema.Column("user_id", "int", false, "下单用户，关联 users.id"),
					new TableSchema.Column("order_date", "date", false, "下单日期"))),
			new TableSchema("users", "注册用户", List.of(
					new TableSchema.Column("id", "int", true, null),
					new TableSchema.Column("region_id", "int", false, "所在大区"))));

	final JsonMapper json = JsonMapper.builder().build();
	final ScriptedChatModel model = new ScriptedChatModel();
	final WarehouseClient warehouse = mock(WarehouseClient.class);
	final WarehouseProperties warehouseProps = new WarehouseProperties("jdbc:mysql://unused", "u", "p",
			DEFAULT_SCHEMA, List.of(DEFAULT_SCHEMA, OTHER_SCHEMA), 15, 1000, Duration.ofMinutes(5));
	final AgentProperties props = AgentProperties.defaults();
	final AgentExecutors executors = new AgentExecutors(Executors.newFixedThreadPool(2),
			Executors.newFixedThreadPool(props.drillDownParallelism()));
	final AnalysisAgent agent;

	AgentKit() throws Exception {
		when(warehouse.listTables(anyString())).thenReturn(TABLES);
		AgentModels models = new AgentModels(model, model, model.streaming(), model.streaming());
		Prompts prompts = new Prompts();
		SchemaCatalog catalog = new SchemaCatalog(warehouse, warehouseProps);
		SqlStepRunner runner = new SqlStepRunner(models, prompts, new SqlGuard(warehouseProps), warehouse, catalog, props);
		agent = new AnalysisAgent(
				new RouteNode(models, prompts, json),
				new SelectTablesNode(models, prompts, catalog, json),
				new QueryNode(runner),
				new DrillDownNode(models, prompts, catalog, runner, executors, props, json),
				new ChartNode(models, prompts, json),
				new ConcludeNode(models, prompts, props));
	}

	@Override
	public void close() {
		executors.close();
	}

}
