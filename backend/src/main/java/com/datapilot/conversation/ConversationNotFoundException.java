package com.datapilot.conversation;

public class ConversationNotFoundException extends RuntimeException {

	public ConversationNotFoundException(long id) {
		super("Conversation " + id + " does not exist");
	}

}
