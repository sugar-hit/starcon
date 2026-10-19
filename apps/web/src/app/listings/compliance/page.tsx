'use client';

import { ListingComplianceWorkspace } from '@/components/listing-compliance/listing-compliance-workspace';

/**
 * 合规预检工作区：L3「4.2.3 平台适配」的锚点路由 `/listings/compliance`。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.2.3.1 平台类目映射</b> —— 内部分类 ↔ 平台类目的多对多映射，附带平台类目必填属性规则
 *       （页面在 {@code /listings/category-mappings}）。
 *   <li><b>4.2.3.2 合规预检（违禁词/侵权）</b> —— 发布前扫描内容的违规风险，产出风险清单
 *       （风险等级 · 命中位置 · 规则来源 · 建议替换）与阻断项 / 提醒项。
 * </ul>
 *
 * <p>两者分属 L3 的两个子节点、解决不同的问题：4.2.3.1 管「平台要哪些属性、我们内部是什么」，
 * 4.2.3.2 管「这份内容能不能发」。所以现在这一页加成页签分开 ——
 * 与 {@code /listings/templates} 里刊登模板 / 描述模板用页签分开是同一手法。
 *
 * <p>🔴 路由刻意用 {@code /listings/compliance} 而不是 {@code /products/compliance}：
 * 后者是 catalog 的商品合规工作区（3.9.2.1 完整度评分），是别人的成果，
 * 两条链路一个是「商品主数据够不够完整」，一个是「内容能不能发」，不混。
 */
export default function ListingCompliancePage() {
  return <ListingComplianceWorkspace />;
}
