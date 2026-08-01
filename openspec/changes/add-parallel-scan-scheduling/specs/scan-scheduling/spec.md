## ADDED Requirements

### Requirement: Parallel scan execution
The system SHALL execute all due `search_queries` for a given scheduler tick concurrently
rather than sequentially, while preserving per-query failure isolation (one query's
exception SHALL NOT prevent other queries from running).

#### Scenario: Multiple sources due at the same tick
- **WHEN** a scheduler tick finds queries for more than one source due at the same time
- **THEN** the scans for those queries run concurrently, not one after another

#### Scenario: One query fails during a parallel tick
- **WHEN** one query's scan throws an unexpected exception during a parallel tick
- **THEN** the exception is logged and does not prevent other queries' scans from
  completing in that same tick

### Requirement: Light scan is the default mode
Each scan run SHALL default to light mode, in which pagination stops as soon as a fetched
page contains only jobs already known to the system (same source, same source job id
already present in the `jobs` table), even if the source's own pagination signal
(`hasMore`/cumulative count vs `total`) indicates more pages remain.

#### Scenario: A page is fully known
- **WHEN** a light-mode scan fetches a page and every job on that page already exists in
  the `jobs` table
- **THEN** the scan stops after that page, even if the source reports more pages available

#### Scenario: A page contains at least one unknown job
- **WHEN** a light-mode scan fetches a page and at least one job on it does not yet exist
  in the `jobs` table
- **THEN** the scan continues to the next page (subject to the existing time-budget and
  duplicate-page safety nets)

### Requirement: Deep scan mode disables early termination
When the elapsed time since the last completed deep scan for a query exceeds the
configured interval (or no deep scan has ever completed for that query), the scan SHALL
run in deep mode: early termination is disabled and pagination continues until the
source's own end-of-results signal, the duplicate-page safety net, or the deep-scan time
budget is reached.

#### Scenario: Deep scan interval has elapsed
- **WHEN** a query's last completed deep scan was longer ago than the configured deep
  scan interval
- **THEN** the next scan for that query runs in deep mode

#### Scenario: Deep scan interval has not elapsed
- **WHEN** a query's last completed deep scan was more recent than the configured deep
  scan interval
- **THEN** the next scan for that query runs in light mode

### Requirement: Deep scan resumes across scheduler ticks
If a deep-mode scan is stopped by its time budget before reaching the end of results, the
system SHALL persist the next page to fetch so that the following deep-mode run for the
same query resumes from that page instead of restarting from page 1.

#### Scenario: Deep scan cut short by time budget
- **WHEN** a deep-mode scan is stopped because its time budget was exceeded
- **THEN** the page it was about to fetch next is persisted, and the deep-scan-completed
  timestamp is not updated

#### Scenario: Deep scan reaches the true end of results
- **WHEN** a deep-mode scan reaches the source's actual last page (or the duplicate-page
  safety net triggers)
- **THEN** the persisted resume page is cleared and the deep-scan-completed timestamp is
  updated to the current time

### Requirement: Light scan does not affect deep scan resume state
A light-mode scan run SHALL NOT read or write the deep-scan resume page or the
deep-scan-completed timestamp, regardless of how or where it stops.

#### Scenario: Light scan stops early
- **WHEN** a light-mode scan stops (whether by early termination, reaching the source's
  end of results, or a safety net)
- **THEN** the deep-scan resume page and deep-scan-completed timestamp are left unchanged
