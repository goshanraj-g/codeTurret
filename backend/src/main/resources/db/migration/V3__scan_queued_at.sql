-- When a scan was queued, so a stalled worker can be detected (ScanQueueMonitor).
ALTER TABLE scans ADD COLUMN queued_at TIMESTAMP NOT NULL DEFAULT now();
