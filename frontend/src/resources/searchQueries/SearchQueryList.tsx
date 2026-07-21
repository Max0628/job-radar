import {
  List,
  Datagrid,
  TextField,
  BooleanField,
  NumberField,
  ArrayField,
  SingleFieldList,
  ChipField,
  EditButton,
  DeleteButton,
} from "react-admin";

// 配置台：search_queries 的列表畫面。這一塊維持 React Admin 標準 Datagrid，
// 不用自訂卡片——欄位固定、操作固定（CRUD），正是 React Admin 最擅長的場景
// （見 add-job-dashboard/design.md D9 的技術棧決策）。
export const SearchQueryList = () => (
  <List>
    <Datagrid>
      <TextField source="source" />
      <TextField source="keyword" emptyText="(空)" />
      <TextField source="location" emptyText="(不限地區)" />
      <ArrayField source="categories">
        <SingleFieldList linkType={false}>
          <ChipField source="." size="small" />
        </SingleFieldList>
      </ArrayField>
      <NumberField source="maxPages" label="掃描頁數" />
      <NumberField source="intervalMinutes" label="間隔(分鐘)" />
      <BooleanField source="enabled" label="啟用中" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  </List>
);
