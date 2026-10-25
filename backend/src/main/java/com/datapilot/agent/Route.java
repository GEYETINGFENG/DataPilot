package com.datapilot.agent;

/**
 * How much work a question needs. Decided once per turn by the router.
 */
public enum Route {

	/** A lookup answered by one query: "how many orders last month?". Fast model, no drill-down. */
	SIMPLE,

	/** Why / compare / trend questions: reasoning model, then drill down into the finding. */
	ANALYSIS

}
