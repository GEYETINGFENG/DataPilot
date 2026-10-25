package com.datapilot.warehouse;

public class SchemaNotAllowedException extends RuntimeException {

	public SchemaNotAllowedException(String schema) {
		super("Database '" + schema + "' is not in datapilot.warehouse.allowed-schemas");
	}

}
