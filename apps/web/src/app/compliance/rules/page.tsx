'use client';

import { ComplianceRuleWorkspace } from '@/components/compliance-rule/compliance-rule-workspace';

/**
 * 规则库：L3「16.2.1 合规规则」下的 16.2.1.1。
 *
 * <p>蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * <strong>规则版本化是关键</strong>：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * <p>🔴 本页是<strong>16.2.1.1 规则库</strong>（前端入口 <code>/compliance/rules</code>），
 * 与 16.1.2.1 的 <code>/compliance/violations</code>（违规与处罚）、
 * 16.1.1.1 的 <code>/compliance/account</code>（账号健康总览）、
 * 16.1.1.2 的 <code>/compliance/metrics</code>（绩效指标监控）<strong>是四个页面</strong>：
 * 一个看「平台罚了我们什么」、一个看「账号现在怎么样」、一个看「指标会不会跌破阈值」、
 * 一个看「我们自己定的合规规则是什么、哪一版正在生效」。
 *
 * <p>🔴 <strong>界面上没有「改词」「改严重度」</strong> —— 只有「发新版本」。
 * 改一个生效中的版本，历史判定就再也解释不清了。
 *
 * <p>🔴 下游 <strong>16.2.1.2 拦截执行</strong>当前<strong>未交付</strong> ——
 * 页面上如实标出，<strong>不编</strong>「立即校验 / 放行」这类入口。
 */
export default function ComplianceRulesPage() {
  return (
    <div className="space-y-4">
      <ComplianceRuleWorkspace />
    </div>
  );
}
