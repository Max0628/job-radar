## ADDED Requirements

### Requirement: Each source's API endpoints are declared in exactly one place
For every job source integration, the base URL and path of each API endpoint the system
calls SHALL be declared in a single shared location in the `common` module. No other
module SHALL declare its own copy of the same base URL or path string.

#### Scenario: Two modules call the same source's API
- **WHEN** both the `collector` module and the `api` module need to call the same
  source's API (e.g. Yourator's base URL, or CakeResume's search endpoint used by both
  its list scraper and its facets client)
- **THEN** both read the URL/path from the same shared constant declared once in
  `common`, rather than each declaring its own copy of the literal string

#### Scenario: An endpoint value needs to change
- **WHEN** a source's API endpoint (base URL or path) changes
- **THEN** updating the single shared constant in `common` is sufficient — no other file
  needs to be edited for the new value to take effect
