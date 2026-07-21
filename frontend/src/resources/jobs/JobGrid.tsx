import { Grid } from "@mui/material";
import { useListContext } from "react-admin";
import { JobCard } from "./JobCard";
import type { Job } from "../../types";

// List 的資料抓取/分頁/篩選機制沿用 React Admin（useListContext 拿到已經處理好的資料），
// 畫面本身換成卡片排版而不是預設 Datagrid（見 JobCard 的說明）。
export const JobGrid = () => {
  const { data, isPending } = useListContext<Job>();

  if (isPending) return null;

  return (
    <Grid container spacing={2} sx={{ p: 2 }}>
      {data?.map((job) => (
        <Grid key={job.id} size={{ xs: 12, sm: 6, md: 4 }}>
          <JobCard job={job} />
        </Grid>
      ))}
    </Grid>
  );
};
