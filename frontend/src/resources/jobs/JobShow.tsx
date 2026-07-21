import { Show, useRecordContext } from "react-admin";
import {
  Card,
  CardContent,
  Typography,
  Chip,
  Stack,
  Link as MuiLink,
  Divider,
} from "@mui/material";
import { FavoriteButton } from "./FavoriteButton";
import type { Job } from "../../types";

function formatSalary(job: Job): string {
  if (job.salaryMin == null && job.salaryMax == null) {
    return "面議";
  }
  const currency = job.salaryCurrency ?? "";
  const min = job.salaryMin?.toLocaleString() ?? "?";
  const max = job.salaryMax?.toLocaleString() ?? "?";
  return `${currency} ${min} - ${max}`;
}

const JobShowContent = () => {
  const job = useRecordContext<Job>();
  if (!job) return null;

  const location =
    job.district && job.city ? `${job.city} ${job.district}` : job.city;

  return (
    <Card>
      <CardContent>
        <Stack
          direction="row"
          justifyContent="space-between"
          alignItems="flex-start"
        >
          <Typography variant="h5">{job.title}</Typography>
          <FavoriteButton job={job} />
        </Stack>

        {job.company && (
          <Typography variant="subtitle1" color="text.secondary">
            {job.company}
          </Typography>
        )}

        <Stack
          direction="row"
          spacing={1}
          sx={{ mt: 2, flexWrap: "wrap", rowGap: 1 }}
        >
          <Chip label={job.source} />
          {location && <Chip label={location} />}
          {job.jobType && <Chip label={job.jobType} />}
          {job.seniorityLevel && <Chip label={job.seniorityLevel} />}
          {job.langName && <Chip label={job.langName} />}
        </Stack>

        <Typography variant="body1" sx={{ mt: 2 }}>
          薪資：{formatSalary(job)}
        </Typography>
        {job.minWorkExpYear != null && (
          <Typography variant="body2">
            最低年資：{job.minWorkExpYear} 年
          </Typography>
        )}
        {job.numberOfOpenings != null && (
          <Typography variant="body2">
            開缺人數：{job.numberOfOpenings}
          </Typography>
        )}

        <Divider sx={{ my: 2 }} />

        <MuiLink href={job.url} target="_blank" rel="noopener noreferrer">
          在原平台開啟
        </MuiLink>

        <Typography
          variant="caption"
          display="block"
          color="text.secondary"
          sx={{ mt: 2 }}
        >
          首次發現：{new Date(job.firstSeenAt).toLocaleString()}
          最後更新：{new Date(job.lastSeenAt).toLocaleString()}
        </Typography>
      </CardContent>
    </Card>
  );
};

// 唯讀——不提供編輯/刪除，職缺資料只能由爬蟲管線寫入
// （見 add-job-dashboard/specs/dashboard-frontend 的唯讀 scenario）。
export const JobShow = () => (
  <Show component="div">
    <JobShowContent />
  </Show>
);
