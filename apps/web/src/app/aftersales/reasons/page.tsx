import { ReasonTaxonomyWorkspace } from '@/components/aftersales/reason-taxonomy-workspace';

/**
 * 11.1.2.1 售后原因分类。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「维护售后原因分类树（质量问题、描述不符、尺寸不符、
 * 物流损坏、买家原因、误购、欺诈退货），支持多选与平台原因码映射，驱动后续的质量归因分析。」
 * 加粗句：「<b>原因口径不统一会让退货率分析失真</b> —— 必须强制选择分类而不是留自由文本。」
 *
 * <p>这是一个**真实工作区**：分类树维护 + 多选归因（主因单选、次因复选、权重必填）+
 * 平台原因码映射 + 统一口径报表，加载 / 空 / 错误三态齐全，没有占位数据。
 *
 * <p>路由取 {@code /aftersales/reasons} —— 蓝图 11 卷 L3 写明的 {@code /aftersales}
 * 下的 11.1.2 原因分类。与已交付的两个入口**并列**而非嵌套：
 *
 * <ul>
 *   <li>{@code /aftersales} —— 11.1.1.1 售后单列表（管「单据」）
 *   <li>{@code /aftersales/knowledge} —— 11.4.2.2 客服知识库（管「内容」）
 *   <li>{@code /aftersales/reasons} —— 11.1.2.1 原因分类（管「口径」）
 * </ul>
 *
 * <p>刻意<b>不</b>做成 {@code /aftersales/cases/{id}/reason} 那种挂在单据下的形态：
 * 原因分类是<b>租户级可维护的口径</b>，不是某张单的附属字段。
 * 挂成单据的子页会让「先维护口径、再逐单归因」这个正确顺序变成「每张单都建一遍分类」。
 */
export default function AftersalesReasonsPage() {
  return <ReasonTaxonomyWorkspace />;
}
