package com.datapilot.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

	private final SqlGuard guard = new SqlGuard(100);

	@Test
	void addsLimitWhenMissing() {
		assertThat(guard.check("SELECT id FROM orders"))
				.isEqualToIgnoringCase("SELECT id FROM orders LIMIT 100");
	}

	@Test
	void keepsSmallerLimit() {
		assertThat(guard.check("SELECT id FROM orders LIMIT 10"))
				.isEqualToIgnoringCase("SELECT id FROM orders LIMIT 10");
	}

	@Test
	void clampsLargerLimit() {
		assertThat(guard.check("SELECT id FROM orders LIMIT 5000"))
				.isEqualToIgnoringCase("SELECT id FROM orders LIMIT 100");
	}

	@Test
	void allowsCteAndAggregation() {
		String sql = """
				WITH q AS (SELECT user_id, COUNT(*) c FROM orders GROUP BY user_id)
				SELECT AVG(c) FROM q""";
		assertThat(guard.check(sql)).containsIgnoringCase("LIMIT 100");
	}

	@Test
	void ignoresKeywordsInsideStringLiterals() {
		assertThat(guard.check("SELECT 'sleep(5)' AS note FROM orders")).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"DELETE FROM orders",
			"UPDATE orders SET status = 'paid'",
			"DROP TABLE orders",
			"INSERT INTO orders (id) VALUES (1)",
			"SELECT 1; DROP TABLE orders",
			"SELECT * FROM orders FOR UPDATE",
			"SELECT SLEEP(10)",
			"SELECT BENCHMARK(1000000, MD5('a'))",
			"SELECT * FROM mysql.user",
			"SELECT * FROM `information_schema`.tables",
			"SELEC id FROM orders",
			""
	})
	void rejectsUnsafeOrInvalidSql(String sql) {
		assertThatThrownBy(() -> guard.check(sql)).isInstanceOf(SqlRejectedException.class);
	}

}
