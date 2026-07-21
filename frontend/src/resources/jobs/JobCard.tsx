import { Card, CardContent, Typography, Chip, Stack, Box } from "@mui/material";
import { Link } from "react-admin";
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

function formatLocation(job: Job): string | null {
  if (job.district && job.city) return `${job.city} ${job.district}`;
  if (job.city) return job.city;
  return null;
}

// 職缺卡片——自訂元件，取代 React Admin 預設的 Datagrid（見 add-job-dashboard/design.md
// D9：職缺瀏覽台希望有求職網站的瀏覽感，不是後台報表的表格排版）。
export const JobCard = ({ job }: { job: Job }) => {
  const location = formatLocation(job);

  return (
    <Card
      variant="outlined"
      sx={{ height: "100%", display: "flex", flexDirection: "column" }}
    >
      <CardContent sx={{ flexGrow: 1 }}>
        <Stack
          direction="row"
          justifyContent="space-between"
          alignItems="flex-start"
        >
          <Typography
            variant="h6"
            component={Link}
            to={`/jobs/${job.id}/show`}
            sx={{ textDecoration: "none", color: "inherit" }}
          >
            {job.title}
          </Typography>
          <FavoriteButton job={job} />
        </Stack>

        {job.company && (
          <Typography variant="body2" color="text.secondary">
            {job.company}
          </Typography>
        )}

        <Typography variant="body2" sx={{ mt: 1 }}>
          {formatSalary(job)}
        </Typography>

        <Stack
          direction="row"
          spacing={1}
          sx={{ mt: 1, flexWrap: "wrap", rowGap: 1 }}
        >
          <Chip label={job.source} size="small" />
          {location && <Chip label={location} size="small" />}
          {job.jobType && <Chip label={job.jobType} size="small" />}
          {job.seniorityLevel && (
            <Chip label={job.seniorityLevel} size="small" />
          )}
        </Stack>

        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary">
            最後更新：{new Date(job.lastSeenAt).toLocaleDateString()}
          </Typography>
        </Box>
      </CardContent>
    </Card>
  );
};
