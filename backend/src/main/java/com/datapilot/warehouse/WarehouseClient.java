package com.datapilot.warehouse;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only gateway to the warehouse.
 * <p>
 * The pool is owned here instead of being exposed as a {@code DataSource} bean, so Spring Boot's
 * auto-configured application datasource stays untouched and nothing else can accidentally write
 * through this connection.
 */
@Component
public class WarehouseClient implements DisposableBean {

	private final HikariDataSource dataSource;
	private final JdbcTemplate jdbc;
	private final WarehouseProperties props;

	public WarehouseClient(WarehouseProperties props) {
		this.props = props;
		HikariConfig config = new HikariConfig();
		config.setPoolName("warehouse");
		config.setJdbcUrl(props.url());
		config.setUsername(props.username());
		config.setPassword(props.password());
		config.setReadOnly(true);
		config.setMaximumPoolSize(5);
		// Let the app start even if the warehouse is down; queries will fail with a clear error.
		config.setInitializationFailTimeout(-1);
		this.dataSource = new HikariDataSource(config);
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout(props.queryTimeoutSeconds());
		this.jdbc.setMaxRows(props.maxRows());
	}

	public List<TableSchema> listTables() {
		Map<String, TableSchema> tables = new LinkedHashMap<>();
		jdbc.query("""
				SELECT TABLE_NAME, TABLE_COMMENT
				FROM information_schema.TABLES
				WHERE TABLE_SCHEMA = ?
				ORDER BY TABLE_NAME
				""", rs -> {
			String name = rs.getString(1);
			tables.put(name, new TableSchema(name, rs.getString(2), new ArrayList<>()));
		}, props.schema());
		jdbc.query("""
				SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_KEY, COLUMN_COMMENT
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = ?
				ORDER BY TABLE_NAME, ORDINAL_POSITION
				""", rs -> {
			TableSchema table = tables.get(rs.getString(1));
			if (table != null) {
				table.columns().add(new TableSchema.Column(
						rs.getString(2), rs.getString(3), "PRI".equals(rs.getString(4)), rs.getString(5)));
			}
		}, props.schema());
		return List.copyOf(tables.values());
	}

	/**
	 * Runs a statement that has already passed {@link com.datapilot.guard.SqlGuard}.
	 */
	public QueryResult query(String sql) {
		long start = System.nanoTime();
		return jdbc.query(sql, rs -> {
			ResultSetMetaData meta = rs.getMetaData();
			List<String> columns = new ArrayList<>();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				columns.add(meta.getColumnLabel(i));
			}
			List<List<Object>> rows = new ArrayList<>();
			while (rs.next()) {
				List<Object> row = new ArrayList<>(columns.size());
				for (int i = 1; i <= columns.size(); i++) {
					row.add(rs.getObject(i));
				}
				rows.add(row);
			}
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			return new QueryResult(columns, rows, rows.size() >= props.maxRows(), elapsedMs);
		});
	}

	@Override
	public void destroy() {
		dataSource.close();
	}

}
