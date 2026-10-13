package com.greentech.scheduler_app.application.exception;
public class AdmissionLimitException extends RuntimeException {
    public AdmissionLimitException() { super("tenant active schedule quota exceeded"); }
}
