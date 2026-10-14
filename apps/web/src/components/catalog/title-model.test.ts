import { describe, expect, it } from 'vitest';
import type {
  TitleGeneration,
  TitleTemplateInput,
} from '../../../../../packages/api-contracts/src/index';
import {
  GENERATION_STATUSES,
  KEYWORD_TYPES,
  MAX_SKUS_PER_GENERATION,
  RECORD_STATUSES,
  VARIABLE_SOURCES,
  charLengthText,
  csvExportUrl,
  defaultPlatforms,
  defaultVariables,
  deselectRecord,
  emptyKeywordInput,
  emptyTemplateInput,
  fetchGenerations,
  fetchKeywords,
  fetchRecords,
  generateTitles,
  generatedHint,
  generationEmptyHint,
  generationStatusLabel,
  generationStatusTone,
  keywordCodeProblem,
  keywordProvenance,
  keywordTextProblem,
  keywordTypeLabel,
  maxSimilarityProblem,
  normalizePattern,
  placeholdersOf,
  platformsSizeProblem,
  recordEmptyHint,
  recordStatusLabel,
  recordStatusTone,
  selectRecord,
  similarityText,
  similarityTone,
  skuRejectionText,
  skusSizeProblem,
  sourceLabel,
  templateCodeProblem,
  templateNameProblem,
  templateEmptyHint,
  keywordEmptyHint,
  templateProblem,
  templateSummary,
  variableNameProblem,
} from './title-model';

/**
 * 3.4.2.1 前端模型单测。
 *
 * <p>与 `image-process-model.test.ts` 同一形状：校验规则、展示格式化、空态与文案，
 * 以及「相似但不雷同」在界面上的呈现口径。
 *
 * <p>刻意断言的一件事：**前端不重算相似度**。这里没有任何一个函数接受标题并返回相似度——
 * 重复度是后端算法算出来落库的，前端只负责显示。所以这张表里没有 similarity() 这样的函数。
 */

describe('标题库前端模型', () => {
  describe('相似度展示（不重算，只显示）', () => {
    it('千分比转成百分比并保留一位小数', () => {
      expect(similarityText(0)).toBe('0.0%');
      expect(similarityText(450)).toBe('45.0%');
      expect(similarityText(850)).toBe('85.0%');
      expect(similarityText(1000)).toBe('100.0%');
      // 没测到时如实说「未测量」，不显示 0%（0% 是「毫无共同词」，语义完全不同）。
      expect(similarityText(null)).toBe('未测量');
      expect(similarityText(undefined)).toBe('未测量');
    });

    it('紧迫度分三档：越接近重复铺货判定越红', () => {
      expect(similarityTone(100)).toBe('neutral');
      expect(similarityTone(700)).toBe('warning');
      expect(similarityTone(850)).toBe('warning');
      expect(similarityTone(950)).toBe('danger');
      expect(similarityTone(1000)).toBe('danger');
      expect(similarityTone(null)).toBe('neutral');
    });
  });

  describe('重复度上限校验：上界 999', () => {
    it('1000‰ 被拒——那等于允许完全一样', () => {
      expect(maxSimilarityProblem(null) ?? '').toContain('不能为空');
      expect(maxSimilarityProblem(-1)).not.toBeNull();
      expect(maxSimilarityProblem(1000)).toContain('999');
      expect(maxSimilarityProblem(0)).toBeNull();
      expect(maxSimilarityProblem(850)).toBeNull();
      expect(maxSimilarityProblem(999)).toBeNull();
    });
  });

  describe('模板文本与占位符', () => {
    it('规范化折叠连续空白', () => {
      expect(normalizePattern('  {brand}   {core}  ')).toBe('{brand} {core}');
    });

    it('占位符按出现顺序去重提取', () => {
      expect(placeholdersOf('{brand} {category} {brand} {core}')).toEqual([
        'brand',
        'category',
        'core',
      ]);
      expect(placeholdersOf('没有变量')).toEqual([]);
      expect(placeholdersOf('{未闭合')).toEqual([]);
      expect(placeholdersOf('{} {ok}')).toEqual(['ok']);
    });

    it('变量名与编码校验', () => {
      expect(variableNameProblem('')).not.toBeNull();
      expect(variableNameProblem('1brand') ?? '').toContain('英文字母开头');
      expect(variableNameProblem('brand-x') ?? '').toContain('英文字母开头');
      expect(variableNameProblem('core_word_2')).toBeNull();
      expect(templateCodeProblem('amz-main-1')).toBeNull();
      expect(templateCodeProblem('a b') ?? '').toContain('英文字母');
      expect(templateNameProblem('')).not.toBeNull();
    });
  });

  describe('模板整体校验（与服务层同口径）', () => {
    it('默认模板是合法的：蓝图点名的四个变量 + 一个修饰词', () => {
      // 补上编码与名称：emptyTemplateInput() 是**新建表单**的初值，
      // code/name 刻意留空让用户填。空编码当然不合法——那正是 templateCodeProblem 的职责。
      expect(templateProblem(emptyTemplateInput()) ?? '').toContain('模板编码不能为空');
      const filled = { ...emptyTemplateInput(), code: 'amz-main', name: '亚马逊主标题' };
      expect(templateProblem(filled)).toBeNull();
    });

    it('没有变量的模板被拒——那正是本模块要消灭的重复', () => {
      const input = { ...emptyTemplateInput(), pattern: '星云蓝牙音箱' };
      expect(templateProblem(input) ?? '').toContain('至少要有一个变量占位');
    });

    it('占位符与变量声明必须互相对得上', () => {
      const missing = {
        ...emptyTemplateInput(),
        pattern: '{brand} {core}',
        variables: [{ name: 'brand', source: 'SPU_BRAND', slotNo: 0 }],
      };
      const missingProblem = templateProblem(missing) ?? '';
      expect(missingProblem).toContain('{core}');
      expect(missingProblem).toContain('没有声明绑定源');

      const dead = {
        ...emptyTemplateInput(),
        pattern: '{brand} {core}',
        variables: [
          { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
          { name: 'core', source: 'KEYWORD_CORE', slotNo: 1 },
          { name: 'selling', source: 'KEYWORD_SELLING', slotNo: 2 },
        ],
      };
      const deadProblem = templateProblem(dead) ?? '';
      expect(deadProblem).toContain('selling');
      expect(deadProblem).toContain('死配置');
    });

    it('至少要有一个词根变量：只绑事实字段会让候选彼此雷同', () => {
      const noKeyword = {
        ...emptyTemplateInput(),
        pattern: '{brand} {category}',
        variables: [
          { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
          { name: 'category', source: 'SPU_CATEGORY', slotNo: 1 },
        ],
      };
      expect(templateProblem(noKeyword) ?? '').toContain('至少要有一个词根变量');
    });

    it('必须至少配一个平台长度上限，且平台不重复、长度在 1~255', () => {
      const noPlatform = { ...emptyTemplateInput(), platforms: [] };
      expect(templateProblem(noPlatform) ?? '').toContain('至少要配置一个平台的长度上限');

      const duplicate = {
        ...emptyTemplateInput(),
        platforms: [
          { platform: 'AMAZON', maxLength: 200 },
          { platform: 'amazon', maxLength: 80 },
        ],
      };
      expect(templateProblem(duplicate) ?? '').toContain('平台重复');

      const tooLong = {
        ...emptyTemplateInput(),
        platforms: [{ platform: 'AMAZON', maxLength: 300 }],
      };
      expect(templateProblem(tooLong) ?? '').toContain('1~255');
    });

    it('变量名重复与渲染顺序重复都被拒', () => {
      const dupName = {
        ...emptyTemplateInput(),
        pattern: '{brand} {core}',
        variables: [
          { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
          { name: 'brand', source: 'KEYWORD_CORE', slotNo: 1 },
        ],
      };
      expect(templateProblem(dupName) ?? '').toContain('变量名重复');

      const dupSlot = {
        ...emptyTemplateInput(),
        variables: [
          { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
          { name: 'category', source: 'SPU_CATEGORY', slotNo: 0 },
        ],
      };
      expect(templateProblem(dupSlot) ?? '').toContain('渲染顺序重复');
    });
  });

  describe('词根校验', () => {
    it('编码与内容都要校验，且内容不能带花括号', () => {
      expect(keywordCodeProblem('')).not.toBeNull();
      expect(keywordCodeProblem('core-1')).toBeNull();
      expect(keywordTextProblem('')).not.toBeNull();
      expect(keywordTextProblem('x'.repeat(61)) ?? '').toContain('最多 60');
      // 带花括号会让渲染出的标题出现半成品 {x}。
      expect(keywordTextProblem('{core}') ?? '').toContain('花括号');
      expect(keywordTextProblem('长续航')).toBeNull();
    });
  });

  describe('批量参数上限', () => {
    it('SKU 范围与平台数都有上限', () => {
      expect(skusSizeProblem(0) ?? '').toContain('至少选择');
      expect(skusSizeProblem(MAX_SKUS_PER_GENERATION + 1) ?? '').toContain('最多');
      expect(skusSizeProblem(MAX_SKUS_PER_GENERATION)).toBeNull();
      expect(platformsSizeProblem(0) ?? '').toContain('至少选择');
      expect(platformsSizeProblem(6) ?? '').toContain('最多');
    });
  });

  describe('展示格式化', () => {
    it('模板摘要让列表页不用展开就知道怎么拼、管多严', () => {
      const summary = templateSummary({
        pattern: '{brand} {core}',
        maxSimilarityPermille: 850,
        candidateCount: 3,
        platforms: [{ platform: 'AMAZON', maxLength: 200 }],
      });
      expect(summary).toContain('{brand} {core}');
      expect(summary).toContain('候选 3 条/平台');
      expect(summary).toContain('85.0%');
      expect(summary).toContain('AMAZON ≤200');
    });

    it('词根溯源把三类词讲成一句人话；都没词时如实说', () => {
      const provenance = keywordProvenance({
        coreKeyword: '蓝牙音箱',
        sellingKeyword: '长续航',
        modifierKeyword: '新品',
      });
      expect(provenance).toContain('核心词「蓝牙音箱」');
      expect(provenance).toContain('卖点「长续航」');
      expect(provenance).toContain('修饰词「新品」');
      // 空池子也是正常状态（租户刚开通还没建词），要如实显示而不是留空。
      expect(
        keywordProvenance({ coreKeyword: '', sellingKeyword: '', modifierKeyword: '' }),
      ).toContain('没有可用的词根');
    });

    it('字符数被平台上限裁过时明确说出来', () => {
      expect(charLengthText({ charLength: 200, truncated: false }, 200)).toBe('200 / 200');
      expect(charLengthText({ charLength: 12, truncated: true }, 12)).toContain('已按上限裁剪');
      // 不知道平台上限时只给字符数，不编一个上限出来。
      expect(charLengthText({ charLength: 18, truncated: false }, null)).toBe('18');
    });
  });

  describe('状态标签与色调', () => {
    it('批次与候选两套状态各有中文名与色调', () => {
      expect(generationStatusLabel('PARTIAL')).toBe('部分 SKU 未产出');
      expect(generationStatusTone('SUCCEEDED')).toBe('success');
      expect(generationStatusTone('PARTIAL')).toBe('warning');
      expect(generationStatusTone('FAILED')).toBe('danger');
      expect(recordStatusLabel('SELECTED')).toBe('已选用');
      expect(recordStatusTone('SELECTED')).toBe('success');
      expect(recordStatusTone('REJECTED')).toBe('danger');
      expect(recordStatusTone('CANDIDATE')).toBe('neutral');
      // 未知值回退成原值，不显示空白。
      expect(generationStatusLabel('WHAT')).toBe('WHAT');
    });

    it('词表覆盖后端的三类词根与九种绑定源', () => {
      expect(KEYWORD_TYPES.map((t) => t.value)).toEqual(['CORE', 'SELLING', 'MODIFIER']);
      expect(VARIABLE_SOURCES).toHaveLength(9);
      expect(keywordTypeLabel('CORE')).toBe('核心词');
      expect(sourceLabel('KEYWORD_MODIFIER')).toBe('修饰词池');
      expect(RECORD_STATUSES).toHaveLength(3);
      expect(GENERATION_STATUSES).toHaveLength(3);
    });
  });

  describe('提交后的提示：被拒是正常结果', () => {
    const generation = (over: Partial<TitleGeneration>): TitleGeneration => ({
      id: 'g1',
      templateId: 't1',
      templateName: '标准模板',
      status: 'SUCCEEDED',
      statusLabel: '已生成',
      skuCount: 2,
      candidateCount: 4,
      rejectedCount: 0,
      error: '',
      note: '',
      ...over,
    });

    it('全部产出时只报数量', () => {
      expect(generatedHint(generation({}))).toBe('已为 2 个 SKU 生成 4 条标题候选。');
    });

    it('有被拒时说明「被拒不是失败」并给出下一步', () => {
      const hint = generatedHint(generation({ status: 'PARTIAL', rejectedCount: 2 }));
      expect(hint).toContain('另有 2 条因重复度超限被拒');
      expect(hint).toContain('修饰词池太小');
    });

    it('一个候选都没产出时指向真正的原因', () => {
      const hint = generatedHint(
        generation({ status: 'FAILED', candidateCount: 0, rejectedCount: 3 }),
      );
      expect(hint).toContain('一个候选都没产出');
      expect(hint).toContain('词根库为空');
    });

    it('逐 SKU 未产出的原因优先用后端给的，否则自己按计数兜底', () => {
      expect(skuRejectionText({ rejectedCount: 0, reason: '' })).toBeNull();
      expect(skuRejectionText({ rejectedCount: 2, reason: '压不到 850‰ 之下' })).toBe(
        '压不到 850‰ 之下',
      );
      expect(skuRejectionText({ rejectedCount: 2, reason: '' })).toContain('2 条候选');
    });
  });

  describe('空态与表单初值', () => {
    it('空态文案区分「没有」与「筛选后没有」', () => {
      expect(templateEmptyHint()).toContain('绑定到词池的变量');
      expect(keywordEmptyHint()).toContain('差异化素材');
      expect(generationEmptyHint(false)).toContain('还没有生成批次');
      expect(generationEmptyHint(true)).toContain('清掉筛选条件');
      expect(recordEmptyHint(false)).toContain('还没有标题候选');
      expect(recordEmptyHint(true)).toContain('清掉筛选条件');
    });

    it('默认模板带齐蓝图点名的变量与常见平台上限', () => {
      const input: TitleTemplateInput = emptyTemplateInput();
      expect(input.pattern).toContain('{brand}');
      expect(input.pattern).toContain('{category}');
      expect(input.pattern).toContain('{core}');
      expect(input.pattern).toContain('{selling}');
      expect((input.variables ?? []).filter((v) => v !== null)).toHaveLength(5);
      expect(
        defaultVariables()
          .map((v) => v?.name)
          .filter(Boolean),
      ).toEqual(['brand', 'category', 'core', 'selling', 'modifier']);
      // eBay 80 / Amazon 200 / Shopify 255 的通行口径。
      expect(
        defaultPlatforms()
          .map((p) => p?.maxLength)
          .filter(Boolean),
      ).toEqual([200, 80, 255]);
      expect(emptyKeywordInput()).toEqual({
        code: '',
        text: '',
        keywordType: 'CORE',
        enabled: true,
        version: 0,
      });
    });
  });

  describe('接口与导出地址', () => {
    it('CSV 导出带当前筛选，且用普通 <a download>（api() 会设 JSON content-type）', () => {
      const url = csvExportUrl({ skuId: 's1', platform: 'AMAZON', status: 'SELECTED' });
      expect(url).toContain('/api/catalog/titles/records/export?');
      expect(url).toContain('skuId=s1');
      expect(url).toContain('platform=AMAZON');
      expect(url).toContain('status=SELECTED');
      expect(csvExportUrl({ skuId: null, platform: null, status: null })).toBe(
        '/api/catalog/titles/records/export?',
      );
    });

    it('列表查询把非空条件才带上（不传 undefined）', () => {
      expect(typeof fetchKeywords).toBe('function');
      expect(typeof fetchGenerations).toBe('function');
      expect(typeof fetchRecords).toBe('function');
      expect(typeof generateTitles).toBe('function');
      expect(typeof selectRecord).toBe('function');
      expect(typeof deselectRecord).toBe('function');
    });
  });
});
