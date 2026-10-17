import { AftersalesCaseDetailWorkspace } from '@/components/aftersales/aftersales-case-detail-workspace';

/**
 * 11.1.1.2 售后单详情。
 *
 * <p>蓝图：「单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、沟通记录、附件与凭证、
 * 处置流程状态、退款与退货关联、平台侧状态、责任判定。」输出句：「详情 + 操作入口 + 时间线。」
 * 加粗句：「<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础。」
 *
 * <p>路由挂在 11.1.1.1 列表页的 {@code /aftersales} 之下（{@code /aftersales/cases/{id}}），
 * 所以详情与列表共用同一个前端入口。11.1.1.1 当初**刻意不占用**这条路由，
 * 正是因为它那时不存在 —— 现在本模块交付了，它正好是这里。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/aftersales/cases/{id}} 的真实聚合查询，
 * 不是一个只有字段名的占位页。
 */
export default async function AftersalesCaseDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const resolved = await params;
  return <AftersalesCaseDetailWorkspace caseId={resolved.id} />;
}
