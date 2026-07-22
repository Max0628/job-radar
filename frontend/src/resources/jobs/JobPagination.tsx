import { useListContext } from "react-admin";
import { Box, IconButton, Typography } from "@mui/material";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";

// react-admin 內建 <Pagination> 頁數多的時候會把 1~N 全部列成按鈕（桌機擠成一整排、
// 手機版乾脆只剩左右箭頭一次翻一頁），頁數一多完全沒辦法快速跳頁。這裡改用原生
// <select>：手機上瀏覽器會生成原生的滾動選單（iOS 是滾輪、Android 是列表對話框），
// 桌機則是一般下拉選單，點開就能直接跳到任一頁，不用一直按下一頁。
export const JobPagination = () => {
  const { page, setPage, perPage, total, isPending } = useListContext();

  if (isPending || !total) {
    return null;
  }

  const totalPages = Math.max(1, Math.ceil(total / perPage));

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 1,
        py: 2,
      }}
    >
      <IconButton
        onClick={() => setPage(page - 1)}
        disabled={page <= 1}
        aria-label="上一頁"
      >
        <ChevronLeftIcon />
      </IconButton>

      <Box
        component="select"
        value={page}
        onChange={(e: React.ChangeEvent<HTMLSelectElement>) =>
          setPage(Number(e.target.value))
        }
        sx={{
          font: "inherit",
          padding: "6px 10px",
          borderRadius: 1,
          border: "1px solid",
          borderColor: "divider",
          backgroundColor: "background.paper",
          color: "text.primary",
        }}
      >
        {Array.from({ length: totalPages }, (_, i) => i + 1).map((p) => (
          <option key={p} value={p}>
            第 {p} 頁
          </option>
        ))}
      </Box>

      <Typography variant="body2" color="text.secondary">
        / 共 {totalPages} 頁(總筆數 {total})
      </Typography>

      <IconButton
        onClick={() => setPage(page + 1)}
        disabled={page >= totalPages}
        aria-label="下一頁"
      >
        <ChevronRightIcon />
      </IconButton>
    </Box>
  );
};
