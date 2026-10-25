package com.datapilot.warehouse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the analytical database the agent queries.
 * <p>
 * This is deliberately separate from the application's own datasource: the agent only ever
 * reaches the warehouse through a read-only account, with a statement timeout and a row cap.
 */
@ConfigurationProperties("datapilot.warehouse")
public record WarehouseProperties(
		String url,
		String username,
		String password,
		String schema,
		@DefaultValue("15") int queryTimeoutSeconds,
		@DefaultValue("1000") int maxRows) {
}
