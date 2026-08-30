WITH delivered_run AS (
    SELECT
        created_at,
        due_at,
        delivered_at,
        extract(epoch FROM (delivered_at - due_at)) * 1000 AS completion_lag_ms
    FROM schedule
    WHERE tenant_id = :'tenant'
      AND schedule_id LIKE :'run_prefix'
      AND state = 'DELIVERED'
      AND delivered_at IS NOT NULL
),
run_stats AS (
    SELECT
        count(*) AS delivered,
        min(created_at) AS first_created_at,
        max(created_at) AS last_created_at,
        min(due_at) AS due_at,
        min(delivered_at) AS first_delivered_at,
        max(delivered_at) AS last_delivered_at,
        percentile_cont(0.50) WITHIN GROUP (ORDER BY completion_lag_ms) AS p50_completion_lag_ms,
        percentile_cont(0.95) WITHIN GROUP (ORDER BY completion_lag_ms) AS p95_completion_lag_ms,
        percentile_cont(0.99) WITHIN GROUP (ORDER BY completion_lag_ms) AS p99_completion_lag_ms,
        percentile_cont(0.999) WITHIN GROUP (ORDER BY completion_lag_ms) AS p999_completion_lag_ms,
        max(completion_lag_ms) AS max_completion_lag_ms
    FROM delivered_run
)
SELECT
    delivered,
    first_created_at,
    last_created_at,
    due_at,
    first_delivered_at,
    last_delivered_at,
    round((extract(epoch FROM (last_created_at - first_created_at)))::numeric, 3) AS creation_span_seconds,
    round((extract(epoch FROM (last_delivered_at - first_delivered_at)))::numeric, 3) AS drain_span_seconds,
    round((delivered / nullif(extract(epoch FROM (last_delivered_at - first_delivered_at)), 0))::numeric, 2)
        AS observed_delivery_throughput_per_second,
    round(p50_completion_lag_ms::numeric, 3) AS p50_completion_lag_ms,
    round(p95_completion_lag_ms::numeric, 3) AS p95_completion_lag_ms,
    round(p99_completion_lag_ms::numeric, 3) AS p99_completion_lag_ms,
    round(p999_completion_lag_ms::numeric, 3) AS p999_completion_lag_ms,
    round(max_completion_lag_ms::numeric, 3) AS max_completion_lag_ms
FROM run_stats;
