package com.datapilot.guard;

public class SqlRejectedException extends RuntimeException {

	public SqlRejectedException(String message) {
		super(message);
	}

}
