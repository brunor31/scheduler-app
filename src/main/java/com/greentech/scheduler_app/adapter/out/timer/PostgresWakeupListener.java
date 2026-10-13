package com.greentech.scheduler_app.adapter.out.timer;

import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="scheduler.timer.wakeup-enabled",havingValue="true",matchIfMissing=true)
public class PostgresWakeupListener implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(PostgresWakeupListener.class);
    private final DataSource dataSource;
    private final ScheduleRecoveryLoader loader;
    @org.springframework.beans.factory.annotation.Value("${scheduler.migration-only:false}")
    private boolean migrationOnly;
    @Override public boolean isAutoStartup() { return !migrationOnly; }
    private volatile boolean running;
    private ExecutorService executor;
    public PostgresWakeupListener(DataSource dataSource,ScheduleRecoveryLoader loader) { this.dataSource=dataSource; this.loader=loader; }
    @Override public synchronized void start() {
        if(running) return;
        running=true;
        executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("postgres-wakeup").factory());
        executor.submit(this::listen);
    }
    private void listen() {
        while(running && !Thread.currentThread().isInterrupted()) {
            try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()) {
                connection.setAutoCommit(true); statement.execute("LISTEN scheduler_wakeup");
                PGConnection pg=connection.unwrap(PGConnection.class);
                loader.loadUpcomingSchedules();
                long lastLoad=System.nanoTime();
                boolean pending=false;
                while(running) {
                    var notifications=pg.getNotifications(200);
                    if(notifications!=null && notifications.length>0) pending=true;
                    if(pending && System.nanoTime()-lastLoad>=100_000_000L) {
                        loader.loadUpcomingSchedules(); lastLoad=System.nanoTime(); pending=false;
                    }
                }
            } catch(Exception exception) {
                if(running) LOG.warn("Wake-up connection unavailable; periodic reconciliation remains active ({})",exception.getClass().getSimpleName());
                try { Thread.sleep(1000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    @Override public synchronized void stop() {
        running=false;
        if(executor!=null) { executor.shutdownNow(); executor=null; }
    }
    @Override public boolean isRunning() { return running; }
}
