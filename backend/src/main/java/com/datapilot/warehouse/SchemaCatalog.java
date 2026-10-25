package com.datapilot.warehouse;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Table metadata per schema, cached for a short time.
 * <p>
 * Reading {@code information_schema} costs a few round trips and the agent needs it on every
 * question, but schemas rarely change. A short TTL keeps it cheap without needing invalidation.
 */
@Component
public class SchemaCatalog {

	private final LoadingCache<String, List<TableSchema>> cache;

	public SchemaCatalog(WarehouseClient warehouse, WarehouseProperties props) {
		this.cache = Caffeine.newBuilder()
				.expireAfterWrite(props.schemaCacheTtl())
				.maximumSize(64)
				.build(warehouse::listTables);
	}

	public List<TableSchema> tables(String schema) {
		return cache.get(schema);
	}

}
