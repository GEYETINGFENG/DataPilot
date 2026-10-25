package com.datapilot.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Any OpenAI-compatible endpoint works (DeepSeek, Qwen via DashScope, OpenAI, a local vLLM...).
 *
 * @param fastModel      cheap model for routing and simple lookups
 * @param reasoningModel stronger model for multi-step analysis
 */
@ConfigurationProperties("datapilot.llm")
public record LlmProperties(
		String baseUrl,
		String apiKey,
		String fastModel,
		String reasoningModel,
		@DefaultValue("60s") Duration timeout,
		@DefaultValue("false") boolean logRequests) {
}
