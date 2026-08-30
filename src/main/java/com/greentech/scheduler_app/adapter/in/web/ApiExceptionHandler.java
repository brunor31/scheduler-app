package com.greentech.scheduler_app.adapter.in.web;

import com.greentech.scheduler_app.application.exception.ScheduleAlreadyExistsException;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(InvalidScheduleException.class)
	ProblemDetail invalidSchedule(InvalidScheduleException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(ScheduleAlreadyExistsException.class)
	ProblemDetail duplicateSchedule(ScheduleAlreadyExistsException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}
}
