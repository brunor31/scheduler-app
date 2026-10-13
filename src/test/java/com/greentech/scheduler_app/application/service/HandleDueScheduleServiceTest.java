package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.domain.model.Schedule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandleDueScheduleServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-29T18:00:00Z");
	private static final Duration LEASE = Duration.ofSeconds(30);
	private final ScheduleRepository repository = mock(ScheduleRepository.class);
	private final ScheduleTarget target = mock(ScheduleTarget.class);
	private final ScheduleMetrics metrics = mock(ScheduleMetrics.class);
	private final HandleDueScheduleService service = new HandleDueScheduleService(
			repository, target, metrics, Clock.fixed(NOW, ZoneOffset.UTC), LEASE);

	@Test
	void neverDispatchesWhenClaimIsRejected() {
		when(repository.tryClaim(eq("tenant-a"), eq("schedule-1"), eq(1L), any(), eq(LEASE)))
				.thenReturn(Optional.empty());

		service.handle("tenant-a", "schedule-1", 1);

		verifyNoInteractions(target, metrics);
		verify(repository, never()).markDelivered(any(), any(), anyLong(), any(), any());
	}

	@Test
	void dispatchesOnlyAfterClaimAndConfirmsWithSameToken() {
		Schedule schedule = claimable();
		when(repository.markDelivered(any(), any(), anyLong(), any(), any())).thenReturn(true);

		service.handle("tenant-a", "schedule-1", 1);

		var order = inOrder(repository, target, metrics);
		ArgumentCaptor<UUID> token = ArgumentCaptor.forClass(UUID.class);
		order.verify(repository).tryClaim(eq("tenant-a"), eq("schedule-1"), eq(1L), token.capture(), eq(LEASE));
		order.verify(target).dispatch(schedule);
		order.verify(repository).markDelivered("tenant-a", "schedule-1", 1, token.getValue(), NOW);
		order.verify(metrics).recordDelivered(Duration.ofSeconds(1), Duration.ZERO);
		assertThat(token.getValue()).isNotNull();
	}

	@Test
	void doesNotReportDeliveryWhenAttemptNoLongerOwnsClaim() {
		claimable();
		when(repository.markDelivered(any(), any(), anyLong(), any(), any())).thenReturn(false);

		service.handle("tenant-a", "schedule-1", 1);

		verify(target).dispatch(any());
		verify(metrics, never()).recordDelivered(any(), any());
	}

	@Test
    void persistsUnknownOutcomeForRetryInsteadOfConfirmingDelivery() {
        claimable();
        doThrow(new IllegalStateException("unknown target outcome")).when(target).dispatch(any());
        when(repository.finishFailure(any(),any(),any(),any())).thenReturn(true);
        service.handle("tenant-a","schedule-1",1);
        verify(repository).finishFailure(any(),any(),argThat(r -> r.outcome()==com.greentech.scheduler_app.domain.model.DispatchResult.Outcome.UNKNOWN),any());
        verify(repository,never()).markDelivered(any(),any(),anyLong(),any(),any());
        verify(metrics).recordAttempt("UNKNOWN");
    }

	@Test
	void rejectsNonPositiveLease() {
		assertThatThrownBy(() -> new HandleDueScheduleService(repository, target, metrics,
				Clock.systemUTC(), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

	private Schedule claimable() {
		Schedule schedule = Schedule.create("tenant-a", "schedule-1", NOW.minusSeconds(1), "{}", NOW.minusSeconds(2));
		when(repository.tryClaim(eq("tenant-a"), eq("schedule-1"), eq(1L), any(), eq(LEASE)))
				.thenReturn(Optional.of(schedule));
		return schedule;
	}
}
