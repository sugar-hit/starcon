'use client';

import { AutoApprovalWorkspace } from '@/components/integrations/auto-approval-workspace';

/**
 * 18 集成开放与智能自动化 → 18.3 业务规则引擎 → 18.3.3 自动审批 → 18.3.3.1 自动审批
 * （卷 18 前端入口 `/integrations/auto-approvals`）。
 *
 * <p>蓝图：「<strong>自动审批</strong>：自动化审批场景：自动通过低风险小额单据、
 * 自动驳回明显不合规单据、金额分层路由审批人、超时自动升级。输入：审批规则配置；单据风险信号。
 * 输出：自动审批结果 + 决策依据记录。关系：上游 = 17.4 审批；下游 = 各业务系统。
 * <strong>自动审批只处理「明确安全」的场景</strong>（小额 + 供应商已验证 + 无风险信号），
 * 其余仍走人工——审批是内控底线。」
 *
 * <p>🔴 本页与 <code>/integrations/connectors/external-integrations</code>（18.2.3.1 其他对接）、
 * <code>/integrations/connectors/payment-funds</code>（18.2.2.1 支付与资金）
 * <strong>不是一件事</strong>：那两页管的是「外部系统怎么连」与「钱怎么出去」，
 * 本页管的是「系统替不替人放行单据」。三者只是同属 18 卷而已 ——
 * 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本页最要紧的一条纪律：<strong>上游 17.4 没有程序化通过/驳回通道</strong>
 * （它的 <code>decide</code> / <code>transfer</code> 都绑定当前审批人自己的会话）。
 * 所以本页管的是「<strong>判定 + 记录 + 转交</strong>」：自动通过 / 自动驳回的结论
 * 落在自己的决策台账里（带当时的规则与信号快照），必须走人工的单据则真的提交给上游人工审批。
 * 页面最上面那一块能力声明不是装饰 —— 它是内控边界的说明。
 */
export default function AutoApprovalsPage() {
  return (
    <div className="space-y-4">
      <AutoApprovalWorkspace />
    </div>
  );
}
