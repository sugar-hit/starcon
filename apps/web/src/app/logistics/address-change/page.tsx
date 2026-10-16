import { WaybillRedirectWorkspace } from '@/components/logistics/waybill-redirect-workspace';

/**
 * 10.2.1.3 改址与取消。
 *
 * <p>蓝图卷 10：「处理运单层面的改址（部分渠道支持且有时限）与取消，联动订单地址变更
 * 与平台通知。」关系栏写着上游是 10.2.1.1、9.1.1.3，下游是承运商接口与 15 客户与客服。
 *
 * <p>蓝图那句加粗约束是本页面存在的理由：
 * **「改址是买家高频诉求，超时的改址请求要明确告知能否成功而不是让客服猜。」**
 * 所以页面上每一条改址单都直接给一句**能照着念给买家的话**，并把「已超时限
 * （再也不会成功）」与「在时限内但渠道没接通（接通后仍可成功）」分成两种颜色 ——
 * 这两种处境要的行动完全相反，混成一种就等于让客服猜。
 *
 * <p>这是一个真实工作区而不是占位页：渠道规则、改址单、取消单、买家通知任务全部落到
 * PostgreSQL 16；加载态 / 空态 / 错误态三态齐全；筛选是真筛选、汇总覆盖筛选后的全量。
 *
 * <p>路由取 {@code /logistics/address-change}，与两条兄弟互不为前缀：
 * {@code /logistics/shipments}（10.2.1.1 运单列表，看有哪些运单）、
 * {@code /logistics/waybills}（10.2.1.2 运单申请，去要单号）。
 *
 * <p>数据来源如实标注：本模块只读 10.2.1.2 的 {@code logistics_waybill_request}
 * （**同库只读**，运单号的唯一写入所有者永远是它）与 9.1.1.3 的订单变更 / 取消单
 * （**只引用不写入**）；承运商侧的改址 / 取消通道 10.1 未交付，页面上如实标为缺口，
 * 绝不凭空受理。
 */
export default function LogisticsWaybillAddressChangePage() {
  return <WaybillRedirectWorkspace />;
}
