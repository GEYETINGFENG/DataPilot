package com.datapilot.warehouse;

import java.util.List;

public record TableSchema(String name, String comment, List<Column> columns) {

	public record Column(String name, String type, boolean primaryKey, String comment) {
	}

}
