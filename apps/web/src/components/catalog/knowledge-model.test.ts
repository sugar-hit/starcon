import { describe, expect, it } from 'vitest';
import {
  ENTRY_TYPES,
  HIT_RATE_SORTS,
  MAX_KEYWORDS,
  MAX_KEYWORD_LENGTH,
  PLATFORMS,
  REVIEW_STATUSES,
  RULE_STATUSES,
  TRANSLATION_STATUSES,
  USAGE_OUTCOMES,
  bodyProblem,
  codeProblem,
  emptyEntryInput,
  emptyTranslationInput,
  entryEmptyHint,
  entryTypeLabel,
  hitRateTone,
  keywordsProblem,
  normalizeLocale,
  outcomeHint,
  outcomeLabel,
  outcomeTone,
  percentText,
  placeholderProblem,
  placeholdersOf,
  reviewEmptyHint,
  reviewStateLabel,
  reviewStatusLabel,
  reviewStatusTone,
  reviewTaskSummary,
  ruleEmptyHint,
  ruleStatusLabel,
  ruleStatusTone,
  titleProblem,
  usageEmptyHint,
} from './knowledge-model';

/**
 * 11.4.2.2 客服知识库与话术前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：命中率不重算、待复核任务要点名接替版本、
 * 空态要说清为什么空。
 */
describe('11.4.2.2 知识库前端模型', () => {
  // ============================================================ 命中率展示

  describe('命中率', () => {
    it('null 显示「—」而不是 0%', () => {
      // 规则 R4：「一条话术都没有」与「话术一条都没被用过」是两件不同的事。
      // 显示 0% 会让前者看起来像后者，运营会去建话术来"修复"一个不存在的问题。
      expect(percentText(null)).toBe('—');
      expect(percentText(undefined)).toBe('—');
      expect(percentText('')).toBe('—');
      expect(percentText(Number.NaN)).toBe('—');
    });

    it('真实数值按一位小数展示', () => {
      expect(percentText(0)).toBe('0.0%');
      expect(percentText(0.3)).toBe('30.0%');
      expect(percentText(0.3333)).toBe('33.3%');
      expect(percentText(1)).toBe('100.0%');
      expect(percentText('0.5')).toBe('50.0%');
    });

    it('色调按阈值分档且 null 走中性', () => {
      expect(hitRateTone(null)).toBe('neutral');
      expect(hitRateTone(0.9)).toBe('success');
      expect(hitRateTone(0.7)).toBe('success');
      expect(hitRateTone(0.5)).toBe('warning');
      expect(hitRateTone(0.1)).toBe('warning');
    });
  });

  // ============================================================ R1 占位符

  describe('占位符（规则 R1）', () => {
    it('一致时通过', () => {
      expect(placeholderProblem('您好 {buyer_name}，订单 {order_no}', 'Hi {buyer_name}, order {order_no}')).toBeNull();
    });

    it('译文漏占位符时报出漏了哪个', () => {
      const problem = placeholderProblem('您好 {buyer_name}，订单 {order_no}', 'Hi buyer_name, order {order_no}');
      expect(problem).toContain('buyer_name');
      expect(problem).toContain('漏抄');
    });

    it('译文多占位符时报出多了哪个与为什么', () => {
      const problem = placeholderProblem('您好 {buyer_name}', 'Hi {buyer_name}, track {tracking_no}');
      expect(problem).toContain('tracking_no');
      expect(problem).toContain('永远填不上');
    });

    it('漏与多同时出现时两个都报', () => {
      const problem = placeholderProblem('{a} {b}', '{a} {c}');
      expect(problem).toContain('b');
      expect(problem).toContain('c');
    });

    it('提取去重且有序', () => {
      expect(placeholdersOf('{b} {a} {b}')).toEqual(['a', 'b']);
    });

    it('出现次数不影响判定', () => {
      expect(placeholderProblem('请确认 {order_no}', '请确认 {order_no} {order_no} {order_no}')).toBeNull();
    });

    it('空花括号与未闭合花括号不算占位符', () => {
      expect(placeholdersOf('{ } 和 {}')).toEqual([]);
      expect(placeholdersOf('{order_no')).toEqual([]);
    });
  });

  // ============================================================ R3 引用结果

  describe('引用结果（规则 R3）', () => {
    it('只有已解决与部分解决计入命中', () => {
      const hit = USAGE_OUTCOMES.filter((o) => o.hit).map((o) => o.value);
      expect(hit).toEqual(['RESOLVED', 'PARTIAL']);
    });

    it('被驳回是 danger，放弃引用与未解决是中性', () => {
      expect(outcomeTone('REJECTED')).toBe('danger');
      expect(outcomeTone('RESOLVED')).toBe('success');
      expect(outcomeTone('PARTIAL')).toBe('warning');
      expect(outcomeTone('ABANDONED')).toBe('neutral');
      expect(outcomeTone('UNRESOLVED')).toBe('neutral');
    });

    it('每种结果都有中文标签与一句解释', () => {
      for (const outcome of USAGE_OUTCOMES) {
        expect(outcomeLabel(outcome.value)).toBe(outcome.label);
        expect(outcomeHint(outcome.value).length).toBeGreaterThan(0);
      }
    });

    it('被驳回的提示要点明它是最该被发现的一种', () => {
      expect(outcomeHint('REJECTED')).toContain('最该被发现');
    });
  });

  // ============================================================ 待复核任务

  describe('待复核任务', () => {
    const task = {
      policyKey: 'AMAZON_RETURN_POLICY',
      versionLabel: '2026-01',
      supersedingRuleLabel: '2026-03',
      supersedingChangeSummary: '退货运费改为买家承担',
    };

    it('要点名接替版本与变更摘要', () => {
      const summary = reviewTaskSummary(task);
      expect(summary).toContain('2026-01');
      expect(summary).toContain('2026-03');
      expect(summary).toContain('退货运费改为买家承担');
    });

    it('没有接替版本时明确告诉运营先去补新版', () => {
      // 「政策改版了，去看看」不是可执行指令 —— 客服不知道该比对哪一版。
      const summary = reviewTaskSummary({ ...task, supersedingRuleLabel: null });
      expect(summary).toContain('还没有登记接替版本');
    });

    it('接替版本存在但没写变更摘要时不编造原因', () => {
      const summary = reviewTaskSummary({ ...task, supersedingChangeSummary: '' });
      expect(summary).toContain('2026-03');
      expect(summary).not.toContain('：');
    });

    it('状态三态都有标签与色调', () => {
      expect(reviewStatusTone('DONE')).toBe('success');
      expect(reviewStatusTone('REVIEWING')).toBe('warning');
      expect(reviewStatusTone('PENDING')).toBe('neutral');
      for (const status of REVIEW_STATUSES) {
        expect(reviewStatusLabel(status.value)).toBe(status.label);
      }
    });

    it('列表筛选词表有三态', () => {
      expect(reviewStateLabel('PENDING')).toBe('有待复核');
      expect(reviewStateLabel('NONE')).toBe('无待复核');
      expect(reviewStateLabel(null)).toBe('全部');
    });
  });

  // ============================================================ 平台规则版本

  describe('平台规则版本', () => {
    it('被接替状态带 hint 说明它会生成待复核任务', () => {
      const superseded = RULE_STATUSES.find((s) => s.value === 'SUPERSEDED');
      expect(superseded?.hint).toContain('自动给挂在它上面的话术生成待复核任务');
    });

    it('草稿不参与挂钩判定', () => {
      expect(RULE_STATUSES.find((s) => s.value === 'DRAFT')?.hint).toContain('不参与挂钩判定');
    });

    it('生效态用 success 色调', () => {
      expect(ruleStatusTone('ACTIVE')).toBe('success');
      expect(ruleStatusTone('SUPERSEDED')).toBe('warning');
      expect(ruleStatusTone('DRAFT')).toBe('neutral');
      expect(ruleStatusLabel('ACTIVE')).toBe('当前生效');
    });
  });

  // ============================================================ 校验规则

  describe('本地校验', () => {
    it('编码只允许大写字母数字与下划线', () => {
      expect(codeProblem('refund_policy')).toBeNull();
      expect(codeProblem('REFUND_POLICY')).toBeNull();
      expect(codeProblem('')).toContain('不能为空');
      expect(codeProblem('退换货')).toContain('大写字母');
      expect(codeProblem('A'.repeat(33))).toContain('32');
    });

    it('正文上限 8000 并说明为什么', () => {
      expect(bodyProblem('标准答法')).toBeNull();
      expect(bodyProblem('x'.repeat(8001))).toContain('8000');
      expect(bodyProblem('x'.repeat(8001))).toContain('一屏');
    });

    it('标题上限 120', () => {
      expect(titleProblem('退货政策')).toBeNull();
      expect(titleProblem('x'.repeat(121))).toContain('120');
    });

    it('触发词是短词且数量受限', () => {
      expect(keywordsProblem('退,退货,换货')).toBeNull();
      expect(keywordsProblem('')).toBeNull();
      expect(keywordsProblem('词'.repeat(MAX_KEYWORD_LENGTH + 1))).toContain(
        String(MAX_KEYWORD_LENGTH),
      );
      expect(keywordsProblem('词'.repeat(MAX_KEYWORD_LENGTH + 1))).toContain('短词');
      expect(
        keywordsProblem(
          Array.from({ length: MAX_KEYWORDS + 1 }, (_, i) => `词${i}`).join(','),
        ),
      ).toContain(`最多 ${MAX_KEYWORDS} 个`);
    });

    it('触发词不能含花括号', () => {
      expect(keywordsProblem('{buyer_name}')).toContain('花括号');
    });
  });

  // ============================================================ 语言与归一化

  describe('语言代码', () => {
    it('按 BCP-47 规范化大小写', () => {
      expect(normalizeLocale('zh-cn')).toBe('zh-CN');
      expect(normalizeLocale('EN-us')).toBe('en-US');
      expect(normalizeLocale('FR')).toBe('fr');
    });
  });

  describe('空表单', () => {
    it('新建话术的默认值是可用的中文口径', () => {
      const input = emptyEntryInput();
      expect(input.entryType).toBe('FAQ');
      expect(input.sourceLocale).toBe('zh-CN');
      expect(input.enabled).toBe(true);
      expect(codeProblem(input.code)).not.toBeNull();
    });

    it('可带入指定编码', () => {
      expect(emptyEntryInput('REFUND_POLICY').code).toBe('REFUND_POLICY');
    });

    it('新译文默认草稿且 origin 人工撰写', () => {
      const input = emptyTranslationInput('en-US');
      expect(input.status).toBe('DRAFT');
      expect(input.origin).toBe('MANUAL');
      expect(input.locale).toBe('en-US');
    });

    it('草稿译文不算能直接发给买家', () => {
      expect(TRANSLATION_STATUSES.find((s) => s.value === 'DRAFT')?.hint).toContain(
        '不算「能直接发给买家」',
      );
    });
  });

  // ============================================================ 空态文案

  describe('空态文案', () => {
    it('空库时告诉运营下一步做什么', () => {
      const hint = entryEmptyHint('', '', '');
      expect(hint).toContain('话术库还是空的');
      expect(hint).toContain('一键引用');
    });

    it('有关键词时说明关键词搜的是编码标题与正文', () => {
      const hint = entryEmptyHint('', '', '退货运费');
      expect(hint).toContain('编码、标题与正文');
    });

    it('看待复核时说明任务是怎么来的', () => {
      const hint = entryEmptyHint('', 'PENDING', '');
      expect(hint).toContain('自动生成');
    });

    it('待复核列表为空时说明触发条件', () => {
      const hint = reviewEmptyHint('PENDING', '');
      expect(hint).toContain('已被接替');
    });

    it('引用为空时说明记录会从哪来', () => {
      expect(usageEmptyHint('')).toContain('逐条落库');
      expect(usageEmptyHint('')).toContain('现算');
    });

    it('被驳回为空是好事而不是问题', () => {
      expect(usageEmptyHint('REJECTED')).toContain('好消息');
    });

    it('规则版本为空时说明为什么要先有它', () => {
      const hint = ruleEmptyHint('', '');
      expect(hint).toContain('话术要挂在某一版规则上');
    });
  });

  // ============================================================ 词表与排序

  describe('词表', () => {
    it('话术类型四个与后端一致', () => {
      expect(ENTRY_TYPES.map((t) => t.value)).toEqual(['FAQ', 'POLICY', 'RETURN_EX', 'OTHER']);
    });

    it('平台五个与 V14 类目树命名空间一致', () => {
      expect(PLATFORMS.map((p) => p.value)).toEqual([
        'AMAZON',
        'SHOPIFY',
        'EBAY',
        'TIKTOK',
        'ALI1688',
      ]);
    });

    it('引用结果五种且顺序按「越好越前」', () => {
      expect(USAGE_OUTCOMES.map((o) => o.value)).toEqual([
        'RESOLVED',
        'PARTIAL',
        'UNRESOLVED',
        'ABANDONED',
        'REJECTED',
      ]);
    });

    it('命中率排序里「最少被使用」与「被驳回最多」都在', () => {
      const values = HIT_RATE_SORTS.map((s) => s.value);
      expect(values).toContain('LEAST_USED');
      expect(values).toContain('REJECTED');
      // 默认按被引用次数：先看谁真的在用
      expect(values[0]).toBe('');
    });
  });

  describe('未知值', () => {
    it('标签原样返回而不是猜一个', () => {
      expect(entryTypeLabel('NOPE')).toBe('NOPE');
      expect(outcomeLabel('NOPE')).toBe('NOPE');
      expect(reviewStatusLabel('NOPE')).toBe('NOPE');
      expect(ruleStatusLabel('NOPE')).toBe('NOPE');
      expect(outcomeHint('NOPE')).toBe('');
      expect(entryTypeLabel(null)).toBe('未知');
    });
  });
});
