package com.datapilot.common;

import com.datapilot.guard.SqlRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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

}
