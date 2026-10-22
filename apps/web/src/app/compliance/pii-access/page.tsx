'use client';

import { PiiAccessWorkspace } from '@/components/pii-access/pii-access-workspace';

/**
 * PII 管理：L3「16.6.1 合规与隐私」下的 16.6.1.1。
 *
 * <p>蓝图：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * <strong>完整地址查看需留痕</strong>——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * <p>🔴 本页是 <strong>16.6.1 PII 管理</strong>（前端入口 <code>/compliance/pii-access</code>），
 * 与 <code>/compliance/buyer-risk</code>（16.5.1.2 风险评分）、
 * <code>/compliance/precheck</code>（16.2.2.1 合规预检）、
 * <code>/compliance/ip-risk</code>（16.4.1.1 侵权库）
 * <strong>是四个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>脱敏值与完整值在界面上分得清清楚楚</strong>：徽章写明「已脱敏」/「完整值」，
 * 按后端给的 <code>masked</code> 标志渲染（不靠值里有没有星号去猜），
 * 「上游没给这一栏」与「（空）」也是两种文案。
 *
 * <p>🔴 <strong>批量导出必须走审批</strong>：页面上就是「申请 → 审批 → 执行」三段，
 * 审批人不能是申请人本人，导出对象必须逐条列明 —— <strong>没有「一键全量导出」</strong>。
 */
export default function PiiAccessPage() {
  return (
    <div className="space-y-4">
      <PiiAccessWorkspace />
    </div>
  );
}
