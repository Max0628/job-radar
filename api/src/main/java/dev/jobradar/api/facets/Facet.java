package dev.jobradar.api.facets;

/**
 * 一個可選的篩選值：id 是實際要送進爬蟲 API 的值（見 SearchQuery.categories/location），
 * name 是給使用者看的顯示文字。多數情況兩者相同（平台本身的分類/地區字串就是人看得懂的
 * 中文），只有 CakeResume 的 professions 代碼（如 it_back-end-engineer）需要另外導出
 * 顯示用的名稱。
 */
public record Facet(String id, String name) {
}
