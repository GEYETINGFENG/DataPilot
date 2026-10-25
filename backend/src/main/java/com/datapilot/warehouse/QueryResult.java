package com.datapilot.warehouse;

import java.util.List;

/**
 * @param truncated true when the row cap was hit, so callers can tell the model the result is partial
 */
public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {
}
