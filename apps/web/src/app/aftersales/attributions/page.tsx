import { ProductIssueAttributionWorkspace } from '@/components/aftersales/product-issue-attribution-workspace';

/**
 * 11.1.2.2 商品问题归因。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「把质量问题<b>归因到具体环节</b>（生产、运输、仓储、描述），
 * 支持<b>批次追溯</b>与<b>照片比对</b>，形成对供应商或流程的<b>改进建议</b>。」
 * 输出：「<b>归因结论 + 批次质量标记</b>。」
 * 加粗句：「<b>批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别</b>，
 * 前者是供应商责任，后者可能是运输或买家因素。」
 *
 * <p>这是一个**真实工作区**：归因单列表 + 批次质量标记（件数 / 环节分布 / 主导环节）+
 * 照片登记与内容指纹比对 + 出归因结论（环节 + 理由 + 改进建议），
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /aftersales/attributions} —— 与 11.1.2.1 原因分类
 * （{@code /aftersales/reasons}，管「口径」）<b>并列</b>而非嵌套：
 * 原因分类是租户级的口径维护，本模块是<b>逐单的责任判定</b>，
 * 两者是「先定义口径，再逐单归因」的关系，不是父子。
 *
 * <p>页面<b>刻意没有</b>「发起供应商索赔」「写入供应商评级」两个入口：
 * 二者属 6.4.2.2 / 6.1.1.3，<b>均未交付</b>；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function AftersalesAttributionsPage() {
  return <ProductIssueAttributionWorkspace />;
}
