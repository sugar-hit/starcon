import { SupplierClaimWorkspace } from '@/components/aftersales/supplier-claim-workspace';

/**
 * 11.2.3.3 供应商索赔。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「对质量原因退货向供应商发起索赔：关联批次与责任判定、
 * 索赔金额、协商记录、赔付与冲减应付处理。」
 * 输出：「索赔单 + 冲减应付或退款记录。」
 * 加粗句：「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。」
 *
 * <p><b>为什么这层有独立页面</b>：本模块是**采购/质量对接人的工作面** ——
 * 挑批次、谈金额、登记赔付、等财务确认，是一段跨角色交错的流程，
 * 不是一段被下游消费的声明。上游 11.2.3.1 质检与判定、11.1.2.2 商品问题归因是它的输入，
 * 下游 12 财务（冲减应付）尚未交付。
 *
 * <p>路由挂在 {@code /aftersales/returns} 之下（L3 11.2 退货管理），
 * 与兄弟页 {@code /aftersales/return-quality}（11.2.3.1 质检与判定）并列。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/supplier-claims} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「直接标记已结算」的入口：蓝图加粗句「<b>索赔不是自动到账</b>」
 * 正是这条规则的落点 —— 已结算只能由「财务确认冲减」这一个动作驱动。
 */
export default function AftersalesSupplierClaimsPage() {
  return <SupplierClaimWorkspace />;
}
