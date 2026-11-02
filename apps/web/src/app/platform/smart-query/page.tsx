'use client';

import { SmartQueryWorkspace } from '@/components/platform/smart-query-workspace';

/**
 * 18.4.1.2 智能查询（卷 18 前端入口 `/platform/smart-query`）。
 *
 * <p>蓝图：「<strong>职责</strong>：结构化智能查询：自然语言转查询条件（筛选、聚合、排序），
 * 生成报表草稿，支持保存为常用查询。<strong>输入</strong>：自然语言查询意图；可用指标与维度清单。
 * <strong>输出</strong>：查询结果表 / 报表配置草稿。<strong>关系</strong>：上游 = 18.4.1.1；
 * 下游 = 13.6.1.1 自定义报表。<strong>自然语言只映射到受控语义层指标，不生成任意 SQL。</strong>」
 *
 * <p>🔴 本页与 <code>/platform/assistant</code>（18.4.1.1 对话助手）<strong>不是一件事</strong>：
 * 那一页是「问一句话得一个答案 + 明细链接」，本页是「把一句话变成**可执行的查询条件**」——
 * 筛选、聚合、排序、报表草稿、可复用的常用查询。
 *
 * <p>🔴 本页<strong>没有</strong>输入 SQL 的入口：指标与维度只能从受控语义层目录里挑，
 * 问句也只会被解析成词表里的码。
 */
export default function SmartQueryPage() {
  return (
    <div className="space-y-4">
      <SmartQueryWorkspace />
    </div>
  );
}
