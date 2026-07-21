import {
  List,
  TextInput,
  NumberInput,
  SelectInput,
  Pagination,
} from "react-admin";
import { JobGrid } from "./JobGrid";

const SOURCE_CHOICES = [
  { id: "yourator", name: "Yourator" },
  { id: "cakeresume", name: "CakeResume" },
];

// 職缺類型跟平台回傳的原始值一致（Yourator 是大寫底線、CakeResume 是小寫底線），
// 不強行統一，見 add-job-dashboard/design.md D3 一貫的「保留平台原始語意」原則。
const JOB_TYPE_CHOICES = [
  { id: "full_time", name: "全職" },
  { id: "FULL_TIME", name: "全職（Yourator）" },
  { id: "part_time", name: "兼職" },
  { id: "contract", name: "約聘" },
  { id: "internship", name: "實習" },
];

const jobFilters = [
  <TextInput key="q" source="q" label="關鍵字" alwaysOn />,
  <TextInput key="district" source="district" label="區" />,
  <TextInput key="city" source="city" label="縣市" />,
  <NumberInput key="salaryMin" source="salaryMin" label="最低薪資" />,
  <NumberInput key="salaryMax" source="salaryMax" label="最高薪資" />,
  <SelectInput
    key="jobType"
    source="jobType"
    label="職缺類型"
    choices={JOB_TYPE_CHOICES}
  />,
  <SelectInput
    key="source"
    source="source"
    label="來源"
    choices={SOURCE_CHOICES}
  />,
];

export const JobList = () => (
  <List
    filters={jobFilters}
    sort={{ field: "lastSeenAt", order: "DESC" }}
    perPage={12}
    pagination={<Pagination rowsPerPageOptions={[12, 24, 48]} />}
    component="div"
  >
    <JobGrid />
  </List>
);
