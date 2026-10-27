package com.datapilot.agent.node;

import com.datapilot.agent.StepResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the steps run so far for prompts: SQL plus a preview of each result.
 */
final class Findings {

	private Findings() {
	}

	static String describe(List<StepResult> steps, int previewRows) {
		return steps.stream().map(s -> s.describe(previewRows)).collect(Collectors.joining("\n"));
	}

}
