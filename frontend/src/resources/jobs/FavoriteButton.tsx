import { useCreate, useDelete, useNotify, useRefresh } from "react-admin";
import { IconButton } from "@mui/material";
import StarIcon from "@mui/icons-material/Star";
import StarBorderIcon from "@mui/icons-material/StarBorder";
import type { Job } from "../../types";

// 收藏切換按鈕。favorites 註冊成 React Admin resource（見 App.tsx），這裡透過
// useCreate/useDelete 走同一個 data provider，不繞過去直接發請求
// （見 add-job-dashboard/design.md D9）。
export const FavoriteButton = ({ job }: { job: Job }) => {
  const [create, { isLoading: isCreating }] = useCreate();
  const [remove, { isLoading: isDeleting }] = useDelete();
  const notify = useNotify();
  const refresh = useRefresh();

  const handleToggle = async (event: React.MouseEvent) => {
    // 卡片本身可能是可點擊連結，收藏按鈕點擊不要一併觸發外層連結
    event.stopPropagation();
    event.preventDefault();

    try {
      if (job.isFavorited && job.favoriteId != null) {
        await remove("favorites", { id: job.favoriteId });
      } else {
        await create("favorites", {
          data: { source: job.source, sourceJobId: job.sourceJobId },
        });
      }
      refresh();
    } catch {
      notify("收藏操作失敗，請稍後再試", { type: "error" });
    }
  };

  return (
    <IconButton
      onClick={handleToggle}
      disabled={isCreating || isDeleting}
      size="small"
      aria-label={job.isFavorited ? "取消收藏" : "加入收藏"}
    >
      {job.isFavorited ? <StarIcon color="warning" /> : <StarBorderIcon />}
    </IconButton>
  );
};
