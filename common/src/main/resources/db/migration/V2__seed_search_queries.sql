INSERT INTO search_queries (source, keyword, max_pages, interval_minutes, enabled)
VALUES ('yourator', 'devops', 10, 120, TRUE);

INSERT INTO scrape_cursors (search_query_id)
SELECT id FROM search_queries WHERE source = 'yourator' AND keyword = 'devops';
