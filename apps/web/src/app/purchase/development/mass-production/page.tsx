import { MassProductionWorkspace } from '@/components/purchase/mass-production-workspace';

/**
 * 6.6.2.2 转量产与建档。
 *
 * <p>蓝图 06 卷：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
 * 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
 * 输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
 *
 * <p>页面必须让五件事看得见：
 *
 * <ul>
 *   <li><b>正式 SKU 是「交给商品中心建的」，不是本页建的</b>：蓝图要求「正式 SKU 建档」，
 *       但 SKU 的<b>写入所有权在商品中心</b>（3.1.2.1 的 {@code POST /catalog/products/{id}/skus}）。
 *       本页只登记<b>建档请求</b>，再经商品中心的授权接口真提交 ——
 *       所以页面显示的是<b>建档请求的状态</b>与商品中心返回的 SKU id / 编码，
 *       <b>不出现「本模块已建 SKU」的说法</b>。</li>
 *   <li><b>「结果未知 · 不要重试」单独一档</b>：商品中心的建档端点<b>不接受幂等键</b>，
 *       超时 / 断连时无法判断建没建出来 ⇒ 页面不提供重试按钮，
 *       只提示「先到商品中心核对该编码是否已存在」。笼统的「失败」会诱导人点重试，
 *       而那可能建出<b>第二个</b> SKU。</li>
 *   <li><b>目标成本取不到就是取不到</b>：不可比时显示「不可比」+ 原因，<b>不显示 0</b>
 *       （目标成本取不到 ≠ 目标成本是 0）；跨币种<b>不折算</b>（5.2.2.1 未交付）。</li>
 *   <li><b>交接如实显示</b>：6.2.1.2 补货建议的起订额与交期是人工维护的策略，本模块
 *       <b>只触发重算</b>，量产参数<b>没有</b>进到它的计算里 ⇒ 页面显示
 *       「量产参数<b>未</b>进入 6.2.1.2」，不假装交接过。</li>
 *   <li><b>做不到的事写在页面上</b>：价格模型（5.3.1.1 未开发）、
 *       生产计划下达 / 组装单（8.4.1，warehouse 独立库）—— 常驻横幅，不假装可用。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/development/mass-production}（6.6 立项与试产分区），
 * 后端命名空间是 {@code /trade/mass-productions} —— 与 6.6.2.1 前端 /purchase/development/trials ↔
 * 后端 /trade/trial-productions 同一纪律。
 */
export default function MassProductionPage() {
  return <MassProductionWorkspace />;
}
