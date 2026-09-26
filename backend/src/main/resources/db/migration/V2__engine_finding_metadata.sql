-- Detection engine v2: record where each finding came from and why its code was analysed.
ALTER TABLE findings ADD COLUMN source   VARCHAR(20);
ALTER TABLE findings ADD COLUMN cwe_id   VARCHAR(20);
ALTER TABLE findings ADD COLUMN ml_score DOUBLE PRECISION;
ALTER TABLE findings ADD COLUMN signals  TEXT;

ALTER TABLE scans ADD COLUMN units_analyzed INT;
ALTER TABLE scans ADD COLUMN lines_analyzed INT;
ALTER TABLE scans ADD COLUMN static_hits    INT;
