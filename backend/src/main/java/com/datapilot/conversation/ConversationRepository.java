package com.datapilot.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conversations and their messages, in the application database (not the warehouse).
 * <p>
 * Timestamps are written from Java in UTC rather than by {@code CURRENT_TIMESTAMP}, so they do
 * not depend on the MySQL server's time zone.
 */
@Repository
public class ConversationRepository {

	private static final RowMapper<StoredMessage> MESSAGE = (rs, i) -> new StoredMessage(
			rs.getString("role"),
			rs.getString("content"),
			rs.getString("trace"),
			rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

	private final JdbcTemplate jdbc;

	public ConversationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long create(String title) {
		LocalDateTime now = now();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO conversation (title, created_at, updated_at) VALUES (?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, title);
			statement.setObject(2, now);
			statement.setObject(3, now);
			return statement;
		}, key);
		return key.getKeyAs(Number.class).longValue();
	}

	public boolean exists(long id) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE id = ?", Integer.class, id);
		return count != null && count > 0;
	}

	/**
	 * @param trace JSON array of the turn's events, or null for user messages
	 */
	public void addMessage(long conversationId, String role, String content, String trace) {
		LocalDateTime now = now();
		jdbc.update("""
				INSERT INTO conversation_message (conversation_id, role, content, trace, created_at)
				VALUES (?, ?, ?, ?, ?)
				""", conversationId, role, content, trace, now);
		jdbc.update("UPDATE conversation SET updated_at = ? WHERE id = ?", now, conversationId);
	}

	/**
	 * All messages, oldest first.
	 */
	public List<StoredMessage> messages(long conversationId) {
		return jdbc.query("""
				SELECT role, content, trace, created_at FROM conversation_message
				WHERE conversation_id = ? ORDER BY created_at, id
				""", MESSAGE, conversationId);
	}

	/**
	 * The last {@code limit} messages, oldest first.
	 */
	public List<StoredMessage> recentMessages(long conversationId, int limit) {
		List<StoredMessage> messages = new ArrayList<>(jdbc.query("""
				SELECT role, content, trace, created_at FROM conversation_message
				WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
				""", MESSAGE, conversationId, limit));
		Collections.reverse(messages);
		return messages;
	}

	private static LocalDateTime now() {
		// Millisecond precision, matching DATETIME(3).
		return LocalDateTime.ofInstant(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZoneOffset.UTC);
	}

}
