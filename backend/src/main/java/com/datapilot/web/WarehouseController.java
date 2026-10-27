package com.datapilot.web;

import com.datapilot.guard.SqlGuard;
import com.datapilot.warehouse.QueryResult;
import com.datapilot.warehouse.TableSchema;
import com.datapilot.warehouse.WarehouseClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Direct access to the warehouse for the UI's schema browser and SQL panel.
 * Queries go through the same guard the agent uses.
 */
@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {

	private final WarehouseClient warehouse;
	private final SqlGuard guard;

	public WarehouseController(WarehouseClient warehouse, SqlGuard guard) {
		this.warehouse = warehouse;
		this.guard = guard;
	}

	/**
	 * @param database schema to list, default schema if omitted; must be in the allowlist
	 */
	@GetMapping("/tables")
	public List<TableSchema> tables(@RequestParam(required = false) String database) {
		return warehouse.listTables(database);
	}

	@PostMapping("/query")
	public QueryResponse query(@Valid @RequestBody QueryRequest request) {
		String sql = guard.check(request.sql());
		return new QueryResponse(sql, warehouse.query(request.database(), sql));
	}

	/**
	 * @param database schema to query, default schema if null; must be in the allowlist
	 */
	public record QueryRequest(@NotBlank String sql, String database) {
	}

	/**
	 * @param sql the statement that actually ran, after the guard applied its row limit
	 */
	public record QueryResponse(String sql, QueryResult result) {
	}

}
