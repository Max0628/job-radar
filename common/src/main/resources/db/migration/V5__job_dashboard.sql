-- add-job-dashboard: 地區欄位、分類篩選、收藏功能

-- 1. jobs 表新增地區欄位（皆可 null，見 design.md D3：Yourator 只能到縣市級、
--    CakeResume 依段數決定粒度；縣市名稱正規化為「台」通用字，不用「臺」異體字）
ALTER TABLE jobs ADD COLUMN city VARCHAR(32);
ALTER TABLE jobs ADD COLUMN district VARCHAR(32);
CREATE INDEX idx_jobs_city_district ON jobs (city, district);

-- 2. search_queries 新增 categories 欄位（JSONB 字串陣列，可 null），沿用既有 attrs 的
--    JSONB 慣例。Yourator 存分類中文名稱、CakeResume 存 professions 代碼（見 design.md D8）
ALTER TABLE search_queries ADD COLUMN categories JSONB;

-- 3. 修正既有 CakeResume 種子資料的 location 值：原本存 "Taipei"，但正確欄位
--    （filters.locations，見 D8 決策3）需要完整 facet 字串才能生效，且 keyword
--    改為 categories 為主要篩選手段，keyword 清空避免 AND 條件過度限縮結果
UPDATE search_queries SET location = '台北市, 台灣', keyword = ''
WHERE source = 'cakeresume' AND location = 'Taipei';

UPDATE search_queries SET keyword = ''
WHERE source = 'yourator';

-- 4. 補上 categories：每個平台一列涵蓋全部 6 個方向（見 design.md D7 更新——
--    Yourator 的 category[] 單一值過濾不可靠，不拆成多列，一次帶滿）
UPDATE search_queries
SET categories = '["後端工程","全端工程","資料庫","DevOps / SRE","雲端工程師","系統架構師"]'::jsonb
WHERE source = 'yourator';

UPDATE search_queries
SET categories = '["it_back-end-engineer","it_full-stack-development","it_devops-system-admin",
                    "it_system-architecture","it_system-network-administrator","it_database"]'::jsonb
WHERE source = 'cakeresume';

-- 5. 新增 favorites 表（單使用者，不需要 user_id，見 design.md D6）
CREATE TABLE favorites (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(32) NOT NULL,
    source_job_id  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, source_job_id)
);
