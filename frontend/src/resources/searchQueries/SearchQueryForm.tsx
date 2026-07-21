import {
  SimpleForm,
  SelectInput,
  TextInput,
  NumberInput,
  BooleanInput,
  AutocompleteArrayInput,
  FormDataConsumer,
  required,
} from "react-admin";
import { YOURATOR_CATEGORIES, CAKERESUME_PROFESSIONS } from "./categoryOptions";

const SOURCE_CHOICES = [
  { id: "yourator", name: "Yourator" },
  { id: "cakeresume", name: "CakeResume" },
];

const youratorCategoryChoices = YOURATOR_CATEGORIES.map((name) => ({
  id: name,
  name,
}));

// source 是受限下拉選單，不開放自由輸入——查表用代碼裡實際註冊的 adapter 對不上時，
// Collector 會靜默跳過、不報錯也不會爬，這裡在輸入端直接擋掉打錯字的可能性
// （見 add-job-dashboard/design.md D5）。
export const SearchQueryForm = () => (
  <SimpleForm>
    <SelectInput
      source="source"
      choices={SOURCE_CHOICES}
      validate={required()}
    />
    <TextInput source="keyword" helperText="可留空，改用下面的分類篩選" />
    <TextInput
      source="location"
      helperText="Yourator 填 area code（如 TPE）；CakeResume 要填完整地區字串（如「台北市, 台灣」），不能只填「Taipei」"
    />
    {/* categories 的選項依 source 而定：Yourator 用分類中文名稱、CakeResume 用
        professions 代碼，兩邊的合法值完全不同，且都是固定清單不開放自由輸入
        （理由同 source 欄位）。用 FormDataConsumer 依當下選的 source 動態換選項。 */}
    <FormDataConsumer>
      {({ formData }) =>
        formData.source === "cakeresume" ? (
          <AutocompleteArrayInput
            source="categories"
            label="Professions"
            choices={CAKERESUME_PROFESSIONS}
            helperText="CakeResume：單一分類即可正確過濾"
          />
        ) : (
          <AutocompleteArrayInput
            source="categories"
            label="分類"
            choices={youratorCategoryChoices}
            helperText="Yourator：至少選 2 個分類，單一分類的過濾行為不可靠（實測發現，見 design.md D8）"
          />
        )
      }
    </FormDataConsumer>
    <NumberInput
      source="maxPages"
      label="掃描頁數"
      defaultValue={10}
      validate={required()}
    />
    <NumberInput
      source="intervalMinutes"
      label="間隔（分鐘）"
      defaultValue={120}
      validate={required()}
    />
    <BooleanInput source="enabled" label="啟用" defaultValue={true} />
  </SimpleForm>
);
