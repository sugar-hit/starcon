'use client';

import { AiEffectWorkspace } from '@/components/ai/ai-effect-workspace';

/**
 * 18.4.3.2 效果评估（卷 18 前端入口 `/ai/effects`）。
 *
 * <p>蓝图：「<strong>职责</strong>：评估 AI 功能效果：翻译采纳率、预测准确度回测、
 * 助手答案有用性反馈，识别低价值功能并下线。<strong>输入</strong>：人工采纳/修改记录；
 * 预测回测结果；用户反馈。<strong>输出</strong>：效果报表 + 改进建议。
 * <strong>关系</strong>：上游 = 18.4 全部；下游 = 18.4 迭代。
 * <strong>采纳率是 AI 功能唯一诚实的指标</strong>——生成了但没人用的功能应下线。」
 *
 * <p>🔴 本页与 <code>/ai/usage</code>（18.4.3.1 使用与配额）**不是一件事**：
 * 那一页管「AI 花了多少、谁花的、额度还剩多少」，本页管「生成了之后**有没有人真的用**」。
 * 两页的路由互不为前缀（<code>/ai/usage</code> vs <code>/ai/effects</code>），
 * 谁也不会吃掉谁。
 *
 * <p>🔴 本页刻意把三条边界写在最上面：采纳率的<b>分母只有一个来源</b>
 * （18.4.3.1 的调用次数台账）；<b>只出建议、不执行下线</b>（本仓没有按能力粒度的
 * AI 功能开关）；<b>助手答案这一维如实答不可用</b>（上游 18.4.1.1 已熔断未交付，
 * 不伪造助手的使用 / 反馈数据）。
 */
export default function AiEffectsPage() {
  return (
    <div className="space-y-4">
      <AiEffectWorkspace />
    </div>
  );
}
