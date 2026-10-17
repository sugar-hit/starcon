import { AftersalesCaseWorkspace } from '@/components/aftersales/aftersales-case-workspace';

/**
 * 11.1.1.1 售后单列表。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「售后单主列表：售后号、关联订单、店铺、类型
 * （退货退款/仅退款/换货/补发/维修）、金额、原因、状态、时效（平台要求回复/处理时限）、
 * 责任人，支持多维筛选与批量处理。」加粗句：「<b>平台时效倒计时必须显眼</b>：
 * 超时会直接影响账号绩效与资金冻结。」
 *
 * <p>这是一个**真实工作区**而不是 {@code DomainPlaceholder} 占位页：本路由此前挂着
 * 一个只有域清单的空壳，任何人打开都看不到一张售后单。
 *
 * <p>路由取 {@code /aftersales} —— 蓝图 11 卷写明的 L3 入口（11.1 售后受理）。
 * 与已交付的 {@code /aftersales/knowledge}（11.4.2.2 客服知识库）**并列**：
 * 知识库管的是「内容」，这里管的是「单据」，挂成父子会让「话术依赖工单」这个
 * 错误印象成立。
 *
 * <p>11.1.1.2 详情交付后，列表行的售后号已是指向 {@code /aftersales/cases/{id}} 的入口。
 * 本模块此前**刻意不占用**那条路由、也不在行上带任何指向它的链接 ——
 * 一个点进去 404 的入口比没有这个入口更危险；那个前提在详情交付后已经不成立。
 */
export default function AftersalesPage() {
  return <AftersalesCaseWorkspace />;
}
