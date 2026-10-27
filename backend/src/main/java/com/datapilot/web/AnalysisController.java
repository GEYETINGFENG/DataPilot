package com.datapilot.web;

import com.datapilot.agent.AnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Analysis turns, streamed as Server-Sent Events. The contract is {@code docs/analysis-api.md}.
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

	private final AnalysisService analysis;

	public AnalysisController(AnalysisService analysis) {
		this.analysis = analysis;
	}

	@PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@Valid @RequestBody AnalysisRequest request) {
		return analysis.start(request.question(), request.conversationId(), request.database(), request.hint());
	}

	public record AnalysisRequest(@NotBlank String question, Long conversationId, String database, String hint) {
	}

}
