package com.datapilot.warehouse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarehousePropertiesTest {

	private static WarehouseProperties props(String schema, List<String> allowed) {
		return new WarehouseProperties("jdbc:mysql://unused", "u", "p", schema, allowed, 15, 1000, Duration.ofMinutes(5));
	}

	@Test
	void allowlistDefaultsToTheDefaultSchema() {
		WarehouseProperties props = props("demo_shop", null);
		assertThat(props.allowedSchemas()).containsExactly("demo_shop");
		assertThat(props.resolveSchema(null)).isEqualTo("demo_shop");
		assertThat(props.resolveSchema(" ")).isEqualTo("demo_shop");
	}

	@Test
	void onlyAllowedSchemasResolve() {
		WarehouseProperties props = props("demo_shop", List.of("demo_shop", "bird_db"));
		assertThat(props.resolveSchema("bird_db")).isEqualTo("bird_db");
		assertThatThrownBy(() -> props.resolveSchema("datapilot")).isInstanceOf(SchemaNotAllowedException.class);
		assertThatThrownBy(() -> props.resolveSchema("BIRD_DB")).isInstanceOf(SchemaNotAllowedException.class);
	}

	@Test
	void defaultSchemaMustBeAllowed() {
		assertThatThrownBy(() -> props("demo_shop", List.of("bird_db")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("demo_shop");
	}

}
