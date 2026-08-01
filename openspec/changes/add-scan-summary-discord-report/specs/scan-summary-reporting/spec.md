## ADDED Requirements

### Requirement: Every successfully finished scan is reported to Discord after a delay
For every scan run that finishes successfully, the system SHALL send a summary message to
the configured Discord webhook once at least 10 minutes have elapsed since the run
finished, and SHALL NOT send more than one summary message per run. Failed runs are out
of scope for this report — they are covered separately by source error alerting (see the
`source-error-alerting` capability), and do not carry the mode/page/job-count data this
report needs (`finishRunFailed` does not populate those fields).

#### Scenario: A scan finished more than 10 minutes ago and has not been reported
- **WHEN** the reporting task runs and finds a `scrape_runs` row with `finished_at` more
  than 10 minutes in the past and no `report_sent_at` value
- **THEN** a summary message is sent to Discord and `report_sent_at` is set to the current
  time

#### Scenario: A scan finished less than 10 minutes ago
- **WHEN** the reporting task runs and finds a `scrape_runs` row with `finished_at` less
  than 10 minutes in the past
- **THEN** no message is sent for that row yet

#### Scenario: A scan has already been reported
- **WHEN** the reporting task runs and finds a `scrape_runs` row that already has a
  `report_sent_at` value
- **THEN** no additional message is sent for that row

### Requirement: Summary content includes mode and new-job count
Each summary message SHALL include the source, scan mode (light or deep), whether the
scan terminated early, the number of pages scanned, the total number of jobs seen, the
number of newly-first-seen jobs for that source within the run's time window, and the
run's duration.

#### Scenario: Reporting a completed light-mode scan
- **WHEN** a summary is generated for a run with `scan_mode = 'light'`
- **THEN** the message indicates light mode and includes whether it terminated early

#### Scenario: Reporting a completed deep-mode scan
- **WHEN** a summary is generated for a run with `scan_mode = 'deep'`
- **THEN** the message indicates deep mode
