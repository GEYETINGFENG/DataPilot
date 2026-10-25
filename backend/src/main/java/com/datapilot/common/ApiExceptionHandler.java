package com.datapilot.common;

import com.datapilot.conversation.ConversationNotFoundException;
import com.datapilot.guard.SqlRejectedException;
import com.datapilot.warehouse.SchemaNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.concurrent.RejectedExecutionException;

/**
 * Errors are returned as RFC 9457 problem details.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(SqlRejectedException.class)
	ProblemDetail sqlRejected(SqlRejectedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("SQL rejected");
		return problem;
	}

	@ExceptionHandler(DataAccessException.class)
	ProblemDetail queryFailed(DataAccessException e) {
		log.warn("Warehouse query failed", e);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_ENTITY, e.getMostSpecificCause().getMessage());
		problem.setTitle("Query failed");
		return problem;
	}

	@ExceptionHandler(SchemaNotAllowedException.class)
	ProblemDetail schemaNotAllowed(SchemaNotAllowedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Database not allowed");
		return problem;
	}

	@ExceptionHandler(ConversationNotFoundException.class)
	ProblemDetail conversationNotFound(ConversationNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Conversation not found");
		return problem;
	}

	@ExceptionHandler(RejectedExecutionException.class)
	ProblemDetail busy(RejectedExecutionException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Too many analyses are running, please try again shortly");
		problem.setTitle("Busy");
		return problem;
	}

}
