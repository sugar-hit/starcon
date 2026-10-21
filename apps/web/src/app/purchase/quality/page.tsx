import { QualityInspectionWorkspace } from '@/components/purchase/quality-inspection-workspace';

/**
 * 6.4.2.1 质检单。
 *
 * <p>蓝图 06 卷：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、
 * 检验结果记录、不合格判定与扣量。」输出「质检单（合格量/不合格量/让步接收量）；
 * 触发 7 库存的质量状态分流」。
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>三量并排</b>：合格量 / 不合格量 / 让步接收量，加起来恰好等于本次质检覆盖的批量 ——
 *       少了任何一个数，就答不出「这批货到底去哪了」。</li>
 *   <li><b>抽样是服务端算的</b>：应抽数量按模板的抽样比例 × 批量 + 夹逼 + 舍入口径算出，
 *       页面只显示算式原文，不让人手填。</li>
 *   <li><b>不合格的货不能直接进可售库存</b>：不合格量单列，按模板指定去往待检或残次；
 *       warehouse 当前没有可用的授权写端点，所以页面写「未分流」并写清影响 ——
 *       绝不让界面显示「已分流」。</li>
 * </ul>
 */
export default function QualityInspectionPage() {
  return <QualityInspectionWorkspace />;
}
