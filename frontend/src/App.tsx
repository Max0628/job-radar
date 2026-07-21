import { Admin, Resource } from "react-admin";
import SearchIcon from "@mui/icons-material/Search";
import TuneIcon from "@mui/icons-material/Tune";
import { Layout } from "./Layout";
import { dataProvider } from "./dataProvider";
import {
  SearchQueryList,
  SearchQueryCreate,
  SearchQueryEdit,
} from "./resources/searchQueries";
import { JobList, JobShow } from "./resources/jobs";

// favorites 不在這裡註冊成 <Resource>——它沒有獨立的畫面（收藏是 jobs 卡片上的按鈕），
// useCreate/useDelete 呼叫時直接帶 "favorites" 這個字串就會走 data provider，
// 不需要先註冊 Resource（Resource 主要是給路由/選單用，見 add-job-dashboard/design.md D9）。
export const App = () => (
  <Admin layout={Layout} dataProvider={dataProvider}>
    <Resource
      name="search-queries"
      options={{ label: "配置台" }}
      icon={TuneIcon}
      list={SearchQueryList}
      create={SearchQueryCreate}
      edit={SearchQueryEdit}
    />
    <Resource
      name="jobs"
      options={{ label: "職缺瀏覽" }}
      icon={SearchIcon}
      list={JobList}
      show={JobShow}
    />
  </Admin>
);
