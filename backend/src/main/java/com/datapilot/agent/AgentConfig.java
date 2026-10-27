package com.datapilot.agent;

import com.datapilot.llm.LlmConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AgentConfig {

	@Bean
	AgentModels agentModels(@Qualifier(LlmConfig.FAST) ChatModel fast,
			@Qualifier(LlmConfig.REASONING) ChatModel reasoning,
			@Qualifier(LlmConfig.FAST_STREAMING) StreamingChatModel fastStreaming,
			@Qualifier(LlmConfig.REASONING_STREAMING) StreamingChatModel reasoningStreaming) {
		return new AgentModels(fast, reasoning, fastStreaming, reasoningStreaming);
	}

	@Bean(destroyMethod = "close")
	AgentExecutors agentExecutors(AgentProperties props) {
		// No queue for whole turns: when every slot is busy the request fails fast with 503
		// instead of waiting behind other users' multi-minute analyses.
		ExecutorService runs = new ThreadPoolExecutor(props.maxConcurrentRuns(), props.maxConcurrentRuns(),
				0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), threads("analysis-"));
		ExecutorService drillDown = Executors.newFixedThreadPool(props.drillDownParallelism(), threads("drill-down-"));
		return new AgentExecutors(runs, drillDown);
	}

	private static ThreadFactory threads(String prefix) {
		AtomicInteger count = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

}
