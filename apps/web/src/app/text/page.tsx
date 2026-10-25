'use client';

import { TextTranslationWorkspace } from '@/components/text/text-translation-workspace';

/**
 * 18.4.2.1 文本与翻译（卷 18 前端入口 `/text`）。
 *
 * <p>蓝图：「<strong>AI 文本能力</strong>：多语言翻译（标题/描述/客服话术，支持保留品牌词与专业术语）、
 * 标题优化与生成、卖点提炼、违禁词规避建议、客服回复草稿。输入：源文本；目标语言；风格要求；术语表。
 * 输出：翻译/生成结果 + 置信度提示 + 可人工编辑。关系：上游 = 3.4.3 多语言、4.2.1.1 编辑器、
 * 15.1.1.2 发送；下游 = 无（结果需人工确认）。
 * <strong>翻译结果必须人工确认后发布</strong>，直接发机器翻译是品牌风险。」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）<strong>不是一件事</strong>：
 * 那一页管的是「谁可以接入我们」，本页管的是「文案怎么翻、怎么检查、怎么确认后发布」。
 * 两者只是同属卷 18 而已。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的 AI / LLM 供应商适配器</strong>，所以「AI 生成」这一格
 * 在页面上如实显示为「未接入」，归 18.4 卷。与 AI 无关的部分全部真实生效：
 * 术语保护、占位符完整性、违禁词命中、长度与字符集校验、置信度的可解释计算、
 * 人工编辑、人工确认与发布闸、客服话术模板的变量绑定。
 */
export default function TextPage() {
  return (
    <div className="space-y-4">
      <TextTranslationWorkspace />
    </div>
  );
}
