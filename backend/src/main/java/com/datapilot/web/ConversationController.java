package com.datapilot.web;

import com.datapilot.conversation.ConversationNotFoundException;
import com.datapilot.conversation.ConversationRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ConversationRepository conversations;
	private final JsonMapper json;

	public ConversationController(ConversationRepository conversations, JsonMapper json) {
		this.conversations = conversations;
		this.json = json;
	}

	/**
	 * Stored messages, oldest first. Assistant messages carry the turn's events so the UI can
	 * replay how the answer was reached.
	 */
	@GetMapping("/{id}/messages")
	public List<MessageView> messages(@PathVariable long id) {
		if (!conversations.exists(id)) {
			throw new ConversationNotFoundException(id);
		}
		return conversations.messages(id).stream()
				.map(m -> new MessageView(m.role(), m.content(), m.trace() == null ? null : json.readTree(m.trace()),
						m.createdAt()))
				.toList();
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MessageView(String role, String content, JsonNode trace, Instant createdAt) {
	}

}
