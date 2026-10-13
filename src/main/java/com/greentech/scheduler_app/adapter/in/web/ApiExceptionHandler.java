package com.greentech.scheduler_app.adapter.in.web;

import com.greentech.scheduler_app.application.exception.ScheduleAlreadyExistsException;
import com.greentech.scheduler_app.application.exception.ScheduleNotFoundException;
import com.greentech.scheduler_app.application.exception.ScheduleStateConflictException;
import com.greentech.scheduler_app.application.exception.ScheduleVersionConflictException;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.greentech.scheduler_app.application.exception.AdmissionLimitException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> quotaExceeded() {
        return org.springframework.http.ResponseEntity.status(429).header("Retry-After", "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "tenant active schedule quota exceeded"));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class})
    ProblemDetail invalidRequest(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields, JSON, timestamp or required header");
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> databaseUnavailable() {
        return org.springframework.http.ResponseEntity.status(503).header("Retry-After", "1")
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Persistence temporarily unavailable"));
    }


	@ExceptionHandler(InvalidScheduleException.class)
	ProblemDetail invalidSchedule(InvalidScheduleException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(ScheduleAlreadyExistsException.class)
	ProblemDetail duplicateSchedule(ScheduleAlreadyExistsException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(ScheduleNotFoundException.class)
	ProblemDetail scheduleNotFound(ScheduleNotFoundException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(ScheduleStateConflictException.class)
	ProblemDetail stateConflict(ScheduleStateConflictException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(ScheduleVersionConflictException.class)
	ProblemDetail versionConflict(ScheduleVersionConflictException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, exception.getMessage());
	}
}
