## ADDED Requirements

### Requirement: 104 list scraper determines pagination from pagination metadata
The 104 list scraper SHALL treat a page as having a next page when the response's
`metadata.pagination.currentPage` is less than `metadata.pagination.lastPage`, and SHALL
stop pagination when they are equal (including the platform's own cap of roughly 3000
reachable results, where `lastPage × pagesize` stays approximately constant regardless of
the requested `pagesize`).

#### Scenario: More pages remain
- **WHEN** a 104 list response has `currentPage` less than `lastPage`
- **THEN** the scraper fetches the next page (subject to the existing time-budget,
  duplicate-page, and early-termination safety nets)

#### Scenario: Last page reached
- **WHEN** a 104 list response has `currentPage` equal to `lastPage`
- **THEN** the scraper stops and reports the scan as reaching the end

### Requirement: 104 requires a detail fetch
Every job discovered by the 104 list scraper SHALL be marked as requiring a detail fetch
(`needsDetail = true`), with the detail identifier derived from the `link.job` URL's
trailing path segment (not the numeric `jobNo` field).

#### Scenario: Discovering a job from the list response
- **WHEN** the list scraper processes an item from `data[]`
- **THEN** the resulting discovered job has `needsDetail = true` and a detail URL built
  from the slug at the end of that item's `link.job` value

### Requirement: 104 detail and list requests apply the same three-way error classification
Both the 104 list scraper and the 104 detail scraper SHALL classify errors the same way as
other sources (see the `source-blocked-detection` capability): 403/503 responses are not
retried and increment the "blocked" anomaly counter; 429/5xx/timeout responses are retried
with backoff; other exceptions propagate without retry.

#### Scenario: 104 list request receives 403
- **WHEN** a 104 list scraper request receives an HTTP 403 response
- **THEN** it is not retried and the "blocked" anomaly counter for source "104" is
  incremented

#### Scenario: 104 detail request receives 403
- **WHEN** a 104 detail scraper request receives an HTTP 403 response
- **THEN** it is not retried and the "blocked" anomaly counter for source "104" is
  incremented

### Requirement: A suspected block automatically disables all 104 search queries
When either the 104 list scraper or the 104 detail scraper receives an HTTP 403 or 503
response, the system SHALL disable every `search_queries` row with `source = "104"`
(setting `enabled = false`) and record the failure reason in that row's `disabled_reason`
column. This SHALL NOT happen automatically for any other source. Re-enabling SHALL only
happen through a manual update that sets `enabled = true`, which SHALL also clear
`disabled_reason` back to null. The system SHALL NOT automatically re-enable a disabled
104 query after any elapsed time.

#### Scenario: 104 list scraper receives 403
- **WHEN** a 104 list scraper request receives an HTTP 403 or 503 response
- **THEN** every `search_queries` row with `source = "104"` is set to `enabled = false`
  with a non-null `disabled_reason`

#### Scenario: 104 detail scraper receives 403
- **WHEN** a 104 detail scraper request receives an HTTP 403 or 503 response
- **THEN** every `search_queries` row with `source = "104"` is set to `enabled = false`
  with a non-null `disabled_reason`

#### Scenario: Manually re-enabling a disabled 104 query
- **WHEN** a user updates a 104 `search_queries` row with `enabled = true`
- **THEN** the row's `disabled_reason` is cleared to null

### Requirement: 104 facets are derived from the platform's static category/area files
The 104 facets client SHALL build its category list by recursively flattening the nested
tree structure returned by the JobCat.json reference file, and its location list from the
Area.json reference file, using each node's `no` as the facet id and `des` as the display
name.

#### Scenario: Flattening a nested category tree
- **WHEN** the JobCat.json response contains a category node with nested child categories
  under its `n` field
- **THEN** every node at every depth of the tree is included as a separate facet entry
