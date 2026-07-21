// Yourator 分類名稱（來自 /api/v4/job_categories，只列出跟軟體/資料/資安/基礎架構相關的
// 分類群組，不含行銷/業務/人資等不相關分類）。category[] 要用這裡的中文名稱字串，
// 不是數字 id——實測數字 id 完全無效，見 add-job-dashboard/design.md D8 決策 1。
export const YOURATOR_CATEGORIES = [
  "前端工程",
  "後端工程",
  "全端工程",
  "行動裝置開發",
  "測試工程",
  "資料庫",
  "DevOps / SRE",
  "區塊鏈工程師",
  "軟體工程師",
  "資料科學家",
  "數據 / 資料分析師",
  "資料工程 / 機器學習",
  "大數據工程師",
  "AI 工程師",
  "資安工程師",
  "資安架構師",
  "雲端工程師",
  "系統架構師",
  "MIS / 網路管理",
] as const;

// CakeResume professions 代碼（來自 available_facets.professions，見
// docs/source-api-notes.md）。single value 即可正確過濾，不像 Yourator 有單值不可靠的限制。
export const CAKERESUME_PROFESSIONS = [
  { id: "it_back-end-engineer", name: "Backend Engineer" },
  { id: "it_front-end-engineer", name: "Frontend Engineer" },
  { id: "it_full-stack-development", name: "Full-Stack Engineer" },
  { id: "it_devops-system-admin", name: "DevOps / System Admin" },
  {
    id: "it_system-network-administrator",
    name: "System / Network Administrator",
  },
  { id: "it_system-architecture", name: "System Architect" },
  { id: "it_database", name: "Database" },
  { id: "it_data-engineer", name: "Data Engineer" },
  { id: "it_data-scientist", name: "Data Scientist" },
  { id: "it_machine-learning-engineer", name: "Machine Learning Engineer" },
  { id: "it_python-developer", name: "Python Developer" },
  { id: "it_java-developer", name: "Java Developer" },
  { id: "it_node-js-developer", name: "Node.js Developer" },
  { id: "it_app-developer", name: "App Developer" },
  { id: "it_software-engineer", name: "Software Engineer" },
  { id: "it_system-analyst", name: "System Analyst" },
  { id: "it_technical-manager", name: "Technical Manager" },
  { id: "it_chief-information-officer", name: "CIO" },
] as const;
