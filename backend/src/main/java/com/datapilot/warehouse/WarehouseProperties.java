package com.datapilot.warehouse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Connection settings for the analytical database the agent queries.
 * <p>
 * This is deliberately separate from the application's own datasource: the agent only ever
 * reaches the warehouse through a read-only account, with a statement timeout and a row cap.
 *
 * @param schema         the schema analysed when a request does not name one
 * @param allowedSchemas every schema a request may name. Several schemas live on the same server
 *                       (the demo shop, BIRD evaluation databases); only these are reachable
 * @param schemaCacheTtl how long table metadata is cached, see {@link SchemaCatalog}
 */
@ConfigurationProperties("datapilot.warehouse")
public record WarehouseProperties(
		String url,
		String username,
		String password,
		String schema,
		List<String> allowedSchemas,
		@DefaultValue("15") int queryTimeoutSeconds,
		@DefaultValue("1000") int maxRows,
		@DefaultValue("5m") Duration schemaCacheTtl) {

	public WarehouseProperties {
		allowedSchemas = allowedSchemas == null || allowedSchemas.isEmpty()
				? List.of(schema)
				: List.copyOf(allowedSchemas);
		// Fail at startup rather than on the first request.
		if (!allowedSchemas.contains(schema)) {
			throw new IllegalStateException("datapilot.warehouse.allowed-schemas " + allowedSchemas
					+ " must contain the default schema '" + schema + "'");
		}
	}

	/**
	 * Resolves the schema a request asked for.
	 *
	 * @param requested schema name from the request, or null for the default
	 * @throws SchemaNotAllowedException if the schema is not in the allowlist
	 */
	public String resolveSchema(String requested) {
		if (requested == null || requested.isBlank()) {
			return schema;
		}
		if (!allowedSchemas.contains(requested)) {
			throw new SchemaNotAllowedException(requested);
		}
		return requested;
	}

}
