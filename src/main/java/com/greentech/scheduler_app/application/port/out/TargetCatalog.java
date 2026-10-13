package com.greentech.scheduler_app.application.port.out;
public interface TargetCatalog {
    String typeFor(String tenantId, String targetId);
}
