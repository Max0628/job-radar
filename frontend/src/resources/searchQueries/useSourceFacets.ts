import { useEffect, useState } from "react";

export interface Facet {
  id: string;
  name: string;
}

interface SourceFacets {
  categories: Facet[];
  locations: Facet[];
}

// 分類/地區選單即時從 GET /api/sources/{source}/facets 拉（後端已快取 12 小時，見
// FacetsService），取代原本寫死在 categoryOptions.ts 的固定清單——平台新增/改名
// 分類時這裡會自動跟著變，不用手動維護（見 add-crawl-improvements design.md）。
export function useSourceFacets(
  source: string | undefined,
): SourceFacets | null {
  const [facets, setFacets] = useState<SourceFacets | null>(null);

  useEffect(() => {
    if (!source) {
      setFacets(null);
      return;
    }

    let cancelled = false;
    setFacets(null);

    fetch(`${import.meta.env.VITE_JSON_SERVER_URL}/sources/${source}/facets`)
      .then((response) => response.json())
      .then((data: SourceFacets) => {
        if (!cancelled) {
          setFacets(data);
        }
      })
      .catch(() => {
        // 平台暫時打不到（例如網路問題）時，選單維持空白讓使用者知道現在拉不到，
        // 不要整個表單壞掉——這只是輔助選單，keyword/categories 欄位本身還是能手動填
        if (!cancelled) {
          setFacets({ categories: [], locations: [] });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [source]);

  return facets;
}
