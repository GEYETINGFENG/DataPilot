package com.datapilot.agent;

import java.io.Serializable;

/**
 * An earlier question and answer in the same conversation, given to the model as context so that
 * follow-ups like "and in 华南?" can reuse the previous query.
 *
 * @param finalSql the query that answered it, null if the turn failed
 */
public record Turn(String question, String finalSql, String conclusion) implements Serializable {
}
