-- Closed cash sessions with the names of the cashier and the closer, for the variance report (ADR-148 item 6,
-- SD-153); read by reporting only (ADR-014). Open sessions have no variance yet and are left out.
CREATE SQL SECURITY INVOKER VIEW v_cash_sessions AS
SELECT s.id              AS session_id,
       s.cashier_id      AS cashier_id,
       u.full_name       AS cashier_name,
       s.closed_by       AS closed_by,
       c.full_name       AS closer_name,
       s.opened_at       AS opened_at,
       s.closed_at       AS closed_at,
       s.opening_amount  AS opening_amount,
       s.expected_amount AS expected_amount,
       s.counted_amount  AS counted_amount,
       s.variance_amount AS variance_amount
FROM cash_sessions s
JOIN users u ON u.id = s.cashier_id
LEFT JOIN users c ON c.id = s.closed_by
WHERE s.status = 'closed';
