'use client';

import { DataRetentionWorkspace } from '@/components/data-retention/data-retention-workspace';

/**
 * 数据出境与留存：L3「16.6 数据合规」下的 16.6.2.1。
 *
 * <p>蓝图：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * <strong>财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先</strong>——实体目录 §5 已明确这条边界。」
 *
 * <p>🔴 本页是 <strong>16.6.2 数据出境与留存</strong>（前端入口 <code>/compliance/data-retention</code>），
 * 与 <code>/compliance/pii-access</code>（16.6.1.1 PII 管理）、
 * <code>/compliance/buyer-risk</code>（16.5.1.2 风险评分）、
 * <code>/compliance/epr</code>（16.3.2.2 EPR）是**四个不同的页面** —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>「保留期与财务留存期冲突」在界面上有完整的裁决展示</strong>：
 * 冲突对（普通留存期 vs 法定财务留存期）、双方期限、裁决结果与依据四件齐全 ——
 * 不只存不用。法定（财务）留存期没走完时，删除请求是<strong>推迟</strong>（写明推到哪天）
 * 而不是被驳回。
 *
 * <p>🔴 <strong>加载 / 空 / 错误 / 读不到</strong>四种状态文案各不相同：
 * 未交付的 12 财务（finance 服务）与下游数据清理任务一律标成
 * <strong>「读不到」+ 归属模块</strong>，不用空列表冒充「没有数据」。
 */
export default function DataRetentionPage() {
  return (
    <div className="space-y-4">
      <DataRetentionWorkspace />
    </div>
  );
}
