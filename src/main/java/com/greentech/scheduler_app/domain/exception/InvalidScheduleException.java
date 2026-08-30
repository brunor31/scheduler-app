package com.greentech.scheduler_app.domain.exception;

public class InvalidScheduleException extends RuntimeException {

	public InvalidScheduleException(String message) {
		super(message);
	}
}
