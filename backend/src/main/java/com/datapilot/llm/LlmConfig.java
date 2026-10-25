package com.datapilot.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two model tiers: a fast one for classification and simple questions, and a reasoning one for
 * multi-step analysis. Which tier a request uses is decided in code, not by the model.
 */
@Configuration
public class LlmConfig {

	public static final String FAST = "fastChatModel";
	public static final String REASONING = "reasoningChatModel";
	public static final String REASONING_STREAMING = "reasoningStreamingChatModel";

	@Bean(FAST)
	ChatModel fastChatModel(LlmProperties props) {
		return OpenAiChatModel.builder()
				.baseUrl(props.baseUrl())
				.apiKey(props.apiKey())
				.modelName(props.fastModel())
				.timeout(props.timeout())
				.logRequests(props.logRequests())
				.logResponses(props.logRequests())
				.build();
	}

	@Bean(REASONING)
	ChatModel reasoningChatModel(LlmProperties props) {
		return OpenAiChatModel.builder()
				.baseUrl(props.baseUrl())
				.apiKey(props.apiKey())
				.modelName(props.reasoningModel())
				.timeout(props.timeout())
				.logRequests(props.logRequests())
				.logResponses(props.logRequests())
				.build();
	}

	@Bean(REASONING_STREAMING)
	StreamingChatModel reasoningStreamingChatModel(LlmProperties props) {
		return OpenAiStreamingChatModel.builder()
				.baseUrl(props.baseUrl())
				.apiKey(props.apiKey())
				.modelName(props.reasoningModel())
				.timeout(props.timeout())
				.logRequests(props.logRequests())
				.logResponses(props.logRequests())
				.build();
	}

}
