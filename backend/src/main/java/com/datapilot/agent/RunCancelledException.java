package com.datapilot.agent;

/**
 * Thrown inside the graph when the listener went away, to stop the turn at the next checkpoint.
 */
public class RunCancelledException extends RuntimeException {

	public RunCancelledException() {
		super("The client disconnected or the stream timed out");
	}

}
