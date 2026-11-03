'use client';

import { AiUsageWorkspace } from '@/components/ai/ai-usage-workspace';

/**
 * 18.4.3.1 使用与配额（卷 18 前端入口 `/ai/usage`）。
 *
 * <p>蓝图：「<strong>职责</strong>：管理 AI 使用：按租户与用户的调用量与成本、配额限制、
 * 模型选择、敏感数据处理策略开关。<strong>输入</strong>：调用记录；配额配置。
 * <strong>输出</strong>：用量报表 + 配额执行。<strong>关系</strong>：上游 = 18.4 全部；
 * 下游 = 17.6.1 计费。」
 *
 * <p>🔴 本页与 <code>/text</code>（18.4.2.1 文本与翻译）<strong>不是一件事</strong>：
 * 那一页管「文案怎么翻、怎么检查、怎么确认后发布」，本页管「AI 花了多少、谁花的、
 * 额度还剩多少、敏感数据怎么处理」。两者只是同属卷 18 而已。
 *
 * <p>🔴 本页刻意把三条边界写在最上面：敏感数据策略的<strong>执行点是各个 AI 能力自己</strong>
 * （本仓没有全局 AI 拦截点）；配额计数的<strong>权威判定</strong>在记用量那一次的数据库原子递增；
 * 下游计费（17.6.1）<strong>自行取数</strong>，本模块不写 finance 表。
 */
export default function AiUsagePage() {
  return (
    <div className="space-y-4">
      <AiUsageWorkspace />
    </div>
  );
}
