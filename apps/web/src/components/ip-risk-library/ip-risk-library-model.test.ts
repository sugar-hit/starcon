import { describe, expect, it } from 'vitest';
import {
  BASE,
  IMPORT_COLUMNS,
  RIGHT_TYPES,
  RISK_LEVELS,
  STATUSES,
  TERM_KINDS,
  brandText,
  emptyState,
  emptyStateKind,
  importOutcomeLabel,
  importOutcomeTone,
  matchTermsText,
  parseImportText,
  platformText,
  riskLevelLabel,
  riskLevelTone,
  rightTypeLabel,
  sourceLabel,
  statusLabel,
  termKindLabel,
  type IpRiskLibraryEntry,
} from './ip-risk-library-model';

/**
 * 16.4.1.1 侵权库 前端模型的展示口径。
 *
 * 🔴 这组用例守的是页面上最容易做丢的五件事：
 *
 * 1. **值域与后端逐字一致**（四种权利类型 / 三级风险等级）。
 * 2. **低风险不是绿色** —— 绿色会让人以为可以无视。
 * 3. **空值写成人话**（未限定平台 / 未登记匹配特征 / 未指定品牌），**不显示空白**。
 * 4. **导入结果三态分开说**（已写入 / 已存在跳过 / 失败）。
 * 5. **导入文本本地先解析**：哪一行、哪个字段，在提交前就指出来。
 */
describe('16.4.1.1 侵权库前端模型', () => {
  it('白名单前缀是独立的一条，不是并进既有 channel 分支', () => {
    expect(BASE).toBe('channel/ip-risk-library');
  });

  it('权利类型恰为四种，风险等级恰为三级', () => {
    expect(RIGHT_TYPES).toEqual(['TRADEMARK', 'PATENT', 'COPYRIGHT', 'IMAGE_RIGHT']);
    expect(RISK_LEVELS).toEqual(['HIGH', 'MEDIUM', 'LOW']);
    expect(STATUSES).toEqual(['ACTIVE', 'DISABLED']);
    expect(TERM_KINDS).toEqual(['KEYWORD', 'PHRASE']);
  });

  it('风险等级的色调：高红、中黄、低中性 —— 低风险不是「没事」', () => {
    expect(riskLevelTone('HIGH')).toBe('danger');
    expect(riskLevelTone('MEDIUM')).toBe('warning');
    expect(riskLevelTone('LOW')).toBe('neutral');
    // 🔴 低风险**不与**「没事」共用绿色。
    expect(riskLevelTone('LOW')).not.toBe('success');
    expect(riskLevelTone(undefined)).toBe('neutral');
  });

  it('人话名齐全，未知编码回落到原值（不编造）', () => {
    expect(rightTypeLabel('TRADEMARK')).toBe('品牌商标');
    expect(rightTypeLabel('IMAGE_RIGHT')).toBe('肖像权');
    expect(riskLevelLabel('HIGH')).toBe('高风险');
    expect(statusLabel('DISABLED')).toBe('已停用');
    expect(termKindLabel('PHRASE')).toBe('短语');
    expect(sourceLabel('IMPORT')).toBe('批量导入');
    expect(rightTypeLabel('NOPE')).toBe('NOPE');
    expect(rightTypeLabel(null)).toBe('未声明');
  });

  it('空值写成人话，不显示空白', () => {
    expect(platformText(['AMAZON', 'EBAY'])).toBe('AMAZON、EBAY');
    expect(platformText([])).toBe('未限定平台');
    expect(platformText(null)).toBe('未限定平台');
    expect(matchTermsText(['a', 'b'])).toBe('a、b');
    expect(matchTermsText([])).toBe('未登记匹配特征');
    const entry = {
      brandName: null,
    } as IpRiskLibraryEntry;
    expect(brandText(entry)).toBe('未指定品牌');
  });

  it('导入结果三态分开说', () => {
    expect(importOutcomeLabel('INSERTED')).toBe('已写入');
    expect(importOutcomeLabel('SKIPPED')).toBe('已存在，跳过');
    expect(importOutcomeLabel('FAILED')).toBe('失败');
    expect(importOutcomeTone('INSERTED')).toBe('success');
    expect(importOutcomeTone('SKIPPED')).toBe('neutral');
    expect(importOutcomeTone('FAILED')).toBe('danger');
  });

  it('空态两态分开说', () => {
    expect(emptyStateKind(0, false)).toBe('NO_DATA');
    expect(emptyStateKind(3, true)).toBe('NO_MATCH');
    expect(emptyState('NO_DATA').title).toBe('还没有任何侵权条目');
    expect(emptyState('NO_MATCH').title).toBe('筛选条件下没有条目');
    const titles = new Set(
      (['NO_DATA', 'NO_MATCH'] as const).map((kind) => emptyState(kind).description),
    );
    expect(titles.size).toBe(2);
  });

  it('导入文本：管道分隔、注释行忽略、空行忽略', () => {
    const parsed = parseImportText(
      [
        '# 这是注释',
        '',
        'TM-1|TRADEMARK|HIGH|Nike 商标|权利人甲|BrandX|注册号 123|AMAZON,EBAY|brandx,brand x',
        'PT-1|PATENT|MEDIUM|某结构专利||||SHOPIFY|',
      ].join('\n'),
    );
    expect(parsed.problems).toEqual([]);
    expect(parsed.rows).toHaveLength(2);
    const [first, second] = parsed.rows;
    expect(first?.entryCode).toBe('TM-1');
    expect(first?.rightType).toBe('TRADEMARK');
    expect(first?.riskLevel).toBe('HIGH');
    expect(first?.platformCodes).toEqual(['AMAZON', 'EBAY']);
    expect(first?.matchTerms.map((t) => t.term)).toEqual(['brandx', 'brand x']);
    expect(second?.platformCodes).toEqual(['SHOPIFY']);
    expect(second?.matchTerms).toEqual([]);
    expect(second?.brandName).toBeNull();
  });

  it('导入文本：哪一行、哪个字段，提交前就指出来', () => {
    const parsed = parseImportText(
      [
        'TM-1|TRADEMARK|HIGH|Nike 商标',
        'TM-2|商标|HIGH|自由文本权利类型',
        'TM-3|TRADEMARK|72|裸数字风险等级',
        '|TRADEMARK|HIGH|没有编码',
        'TM-5|TRADEMARK|HIGH|',
      ].join('\n'),
    );
    // 🔴 四行坏行全部被点名（行号 + 原因），只有第一行进了 rows。
    expect(parsed.rows).toHaveLength(1);
    expect(parsed.problems).toHaveLength(4);
    expect(parsed.problems.map((p) => p.lineNo)).toEqual([2, 3, 4, 5]);
    const messages = parsed.problems.map((p) => p.message);
    expect(messages[0]).toContain('权利类型');
    expect(messages[1]).toContain('风险等级');
    expect(messages[2]).toContain('条目编码');
    expect(messages[3]).toContain('标题');
  });

  it('导入文本：列数不足也报错，不静默吞掉', () => {
    const parsed = parseImportText('TM-1|TRADEMARK');
    expect(parsed.rows).toHaveLength(0);
    expect(parsed.problems).toHaveLength(1);
    expect(parsed.problems.map((p) => p.message)[0]).toContain('列');
  });

  it('导入列序是公开的（用户得知道怎么粘）', () => {
    expect(IMPORT_COLUMNS[0]).toBe('条目编码');
    expect(IMPORT_COLUMNS[1]).toBe('权利类型');
    expect(IMPORT_COLUMNS[2]).toBe('风险等级');
    expect(IMPORT_COLUMNS[3]).toBe('标题');
  });
});
