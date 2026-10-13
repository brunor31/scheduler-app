package com.greentech.scheduler_app.application.exception;
import com.greentech.scheduler_app.domain.model.DispatchResult;
public class DispatchFailureException extends RuntimeException {
    private final DispatchResult result;
    public DispatchFailureException(DispatchResult result) { super(result.code()); this.result=result; }
    public DispatchResult result() { return result; }
}
