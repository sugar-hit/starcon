import { DefectiveGoodsWorkspace } from '@/components/purchase/defective-goods-workspace';

/**
 * 6.4.2.2 不良品处理。
 *
 * <p>蓝图 06 卷：「不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
 * 处置结论联动库存状态与供应商索赔。」输出「处置单 + 库存状态变更指令 + 供应商索赔记录」。
 *
 * <p>页面必须让四条硬要求看得见：
 *
 * <ul>
 *   <li><b>处置量不接受手填</b>：不合格量 / 可售量上限 / 还能处置多少三个数并排显示，
 *       让人看得见上限在哪 —— 数量全部从 6.4.2.1 质检单回读。</li>
 *   <li><b>让步接收需要权限与原因</b>：表单两者必填，页面写明这一档权限比质检结论录入高，
 *       质检员不能随手放行。</li>
 *   <li><b>库存状态照实显示</b>：warehouse 没有可用的授权写端点 ⇒ 页面写「上游无写端点」
 *       并写清影响，绝不让界面显示「已登记」而其实没动库存。</li>
 *   <li><b>索赔金额不假装已入账</b>：登记金额与已生效冲减金额分列显示，
 *       12 财务未交付时已生效金额恒为 0。</li>
 * </ul>
 */
export default function DefectiveGoodsPage() {
  return <DefectiveGoodsWorkspace />;
}
