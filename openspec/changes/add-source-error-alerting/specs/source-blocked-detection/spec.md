## ADDED Requirements

### Requirement: Suspected-blocking responses are not retried
When a source's list scraper receives an HTTP 403 or 503 response, the system SHALL NOT
retry the request. It SHALL immediately fail the scan for that page and increment an
anomaly counter tagged with reason "blocked" for that source.

#### Scenario: Source returns 403
- **WHEN** a list scraper request receives an HTTP 403 response
- **THEN** the request is not retried, the scan fails, and the "blocked" anomaly counter
  for that source is incremented

#### Scenario: Source returns 503
- **WHEN** a list scraper request receives an HTTP 503 response
- **THEN** the request is not retried, the scan fails, and the "blocked" anomaly counter
  for that source is incremented

#### Scenario: Source returns 429 (unaffected by this change)
- **WHEN** a list scraper request receives an HTTP 429 response
- **THEN** the existing retry-with-backoff behavior still applies (not treated as
  "blocked")

### Requirement: A single blocked event triggers an alert
The alerting system SHALL fire an alert the first time a source's "blocked" anomaly
counter increases, without waiting for the event to repeat or persist over a time window.

#### Scenario: Blocked anomaly counter increases once
- **WHEN** a source's "blocked" anomaly counter increases by any amount within the
  evaluation window
- **THEN** the `JobRadarSourceBlocked` alert fires for that source

#### Scenario: No blocked events occur
- **WHEN** a source's "blocked" anomaly counter does not increase
- **THEN** the `JobRadarSourceBlocked` alert does not fire for that source
