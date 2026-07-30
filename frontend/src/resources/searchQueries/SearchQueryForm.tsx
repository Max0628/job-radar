import {
  SimpleForm,
  SelectInput,
  NumberInput,
  BooleanInput,
  AutocompleteInput,
  AutocompleteArrayInput,
  FormDataConsumer,
  required,
} from "react-admin";
import { useSourceFacets } from "./useSourceFacets";

const SOURCE_CHOICES = [
  { id: "yourator", name: "Yourator" },
  { id: "cakeresume", name: "CakeResume" },
];

// 分類跟地區的合法值依 source 而定，且都是即時從平台官方 API 拉的清單（見
// useSourceFacets），不開放自由輸入——查表對不上時 Collector 會靜默跳過、不報錯
// 也不會爬，這裡在輸入端直接擋掉打錯字/填錯格式的可能性（理由同 source 欄位，
// 見 add-job-dashboard/design.md D5）。抽成獨立元件是因為 hook 不能在
// FormDataConsumer 的 render prop 裡直接呼叫（不是合法的 component 邊界）。
const CategoryAndLocationInputs = ({ source }: { source?: string }) => {
  const facets = useSourceFacets(source);
  const categoryChoices = facets?.categories ?? [];
  const locationChoices = facets?.locations ?? [];
  const loading = source != null && facets === null;

  return (
    <>
      <AutocompleteInput
        source="location"
        label="地區"
        choices={locationChoices}
        helperText={loading ? "選單載入中…" : "不限地區可留空"}
      />
      {source === "cakeresume" ? (
        <AutocompleteArrayInput
          source="categories"
          label="Professions"
          choices={categoryChoices}
          validate={required()}
          helperText={
            loading ? "選單載入中…" : "CakeResume：單一分類即可正確過濾"
          }
        />
      ) : (
        <AutocompleteArrayInput
          source="categories"
          label="分類"
          choices={categoryChoices}
          validate={required()}
          helperText={
            loading
              ? "選單載入中…"
              : "Yourator：至少選 2 個分類，單一分類的過濾行為不可靠（實測發現，見 design.md D8）"
          }
        />
      )}
    </>
  );
};

export const SearchQueryForm = () => (
  <SimpleForm>
    <SelectInput
      source="source"
      choices={SOURCE_CHOICES}
      validate={required()}
    />
    <FormDataConsumer>
      {({ formData }) => <CategoryAndLocationInputs source={formData.source} />}
    </FormDataConsumer>
    <NumberInput
      source="intervalMinutes"
      label="間隔（分鐘）"
      defaultValue={120}
      validate={required()}
    />
    <BooleanInput source="enabled" label="啟用" defaultValue={true} />
  </SimpleForm>
);
