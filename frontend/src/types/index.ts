import type { RaRecord } from "react-admin";

// 對應後端 common.domain.SearchQuery（見 api/src/main/java/dev/jobradar/api/searchquery）
export interface SearchQuery extends RaRecord {
  source: "yourator" | "cakeresume";
  keyword: string;
  location: string | null;
  categories: string[] | null;
  maxPages: number;
  intervalMinutes: number;
  enabled: boolean;
}

// 對應後端 api.job.JobResponse
export interface Job extends RaRecord {
  source: string;
  sourceJobId: string;
  title: string;
  company: string | null;
  salaryMin: number | null;
  salaryMax: number | null;
  salaryCurrency: string | null;
  url: string;
  status: string;
  employmentType: string | null;
  seniorityLevel: string | null;
  jobType: string | null;
  langName: string | null;
  minWorkExpYear: number | null;
  numberOfOpenings: number | null;
  city: string | null;
  district: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  isFavorited: boolean;
  // 取消收藏要用這個 id 呼叫 DELETE /api/favorites/:id，Job 本身沒有這個 id
  // （見 api.job.JobResponse 的實作備註）。未收藏時是 null。
  favoriteId: number | null;
}

// 對應後端 common.domain.Favorite
export interface Favorite extends RaRecord {
  source: string;
  sourceJobId: string;
  createdAt: string;
}
