import { describe, expect, it } from 'vitest';
import type {
  DuplicateCandidateDetail,
  DuplicateCandidateRow,
  DuplicateDecisionRow,
  DuplicateScanRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from './api';
import {
  canSubmitDecision,
  decisionButtonLabel,
  decisionPayload,
  DEFAULT_MIN_SIMILARITY,
  dimensionLabel,
  durationText,
  errorText,
  executionHint,
  primaryOptions,
  referenceSummary,
  referenceTotal,
  scopeWarning,
  signalSummary,
  similarityText,
  timeText,
  topScore,
  validateMinSimilarity,
  type DecisionDraft,
} from './duplicate-model';

/**
 * 3.9.1.1 重复 SKU 识别的前端纯逻辑单测。
 *
 * <p>前端在这一页上唯一能「说错话」的地方就是把后端的结论翻译成人话：
 * 把范围收窄的扫描说成「已清理干净」、把登记决定说成已经合并、
 * 把九类引用全列出来让人看不出哪几项在挡路。这几条都在下面钉死。
 */

const scan = (overrides: Partial<DuplicateScanRow> = {}): DuplicateScanRow =>
  ({
    id: 's1',
    minSimilarity: 0.8,
    scopeCategory: '',
    scopeBrand: '',
    scopeApplied: false,
    scannedSkuCount: 120,
    scannedSppuCount: 40,
    candidateCount: 7,
    signalCount: 11,
    startedAt: '2026-10-07T08:00:00Z',
    finishedAt: '2026-10-07T08:00:01Z',
    durationMs: 420,
    actorId: 'u1',
    pendingCount: 5,
    mergeCount: 1,
    keepCount: 1,
    ...overrides,
  }) as DuplicateScanRow;

const candidate = (overrides: Partial<DuplicateCandidateRow> = {}): DuplicateCandidateRow =>
  ({
    id: 'c1',
    scanId: 's1',
    leftSkuId: 'sku-left',
    leftSkuCode: 'L-1',
    leftSkuName: '左',
    leftSpuCode: 'P-1',
    leftSpuName: '商品甲',
    rightSkuId: 'sku-right',
    rightSkuCode: 'R-1',
    rightSkuName: '右',
    rightSpuCode: 'P-2',
    rightSpuName: '商品乙',
    similarity: 1,
    overlapDimensionCount: 2,
    dimensions: [
      { dimension: 'BARCODE', label: '同条码多 SKU', score: 1, evidence: '撞了 6901234567892' },
      { dimension: 'SIMILAR_PRODUCT', label: '近似标题+相似图片疑似同品', score: 0.85, evidence: '1 张图' },
    ],
    leftReferenceCount: 2,
    rightReferenceCount: 0,
    reason: '命中 2 条线',
    decision: null as unknown as string,
    decisionLabel: '待确认',
    primarySkuId: null as unknown as string,
    decidedAt: null as unknown as string,
    ...overrides,
  }) as DuplicateCandidateRow;

const detail = (overrides: Partial<DuplicateCandidateDetail> = {}): DuplicateCandidateDetail =>
  ({
    candidate: candidate(),
    leftVariantCombination: 'COLOR=红',
    rightVariantCombination: 'COLOR=红',
    leftBarcodeValues: ['6901234567892'],
    rightBarcodeValues: ['6901234567892'],
    leftReferences: [
      { dimension: 'BARCODE', label: '条码与物料标识', count: 1 },
      { dimension: 'MEDIA', label: '素材引用', count: 1 },
      { dimension: 'QUOTE', label: '供应商报价', count: 0 },
    ],
    rightReferences: [],
    signals: [],
    decision: null as unknown as DuplicateDecisionRow,
    nextStepHint: '本模块只登记决定，不执行合并。',
    ...overrides,
  }) as DuplicateCandidateDetail;

describe('相似度与维度', () => {
  it('相似度按百分比展示并保留一位小数', () => {
    expect(similarityText(1)).toBe('100.0%');
    expect(similarityText(0.85)).toBe('85.0%');
    expect(similarityText(0.8333)).toBe('83.3%');
    // 四舍五入到整数会把 0.833 与 0.845 变成同一档，而这两个候选的处理优先级不同。
    expect(similarityText(0.8333)).not.toBe(similarityText(0.845));
    expect(similarityText(null)).toBe('—');
    expect(similarityText(undefined)).toBe('—');
  });

  it('维度名未知时原样返回而不是吞掉', () => {
    expect(dimensionLabel('BARCODE')).toBe('同条码多 SKU');
    expect(dimensionLabel('SIMILAR_PRODUCT')).toBe('近似标题+相似图片疑似同品');
    // 页面上出现陌生维度名本身就是问题（前后端词表不同步），静默显示「未知」会把它藏起来。
    expect(dimensionLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('判读摘要先说命中几条线再说最高分', () => {
    expect(signalSummary(candidate())).toContain('命中 2 条线');
    expect(signalSummary(candidate())).toContain('同条码多 SKU');
    // 重叠维度数与明细条数不一致时要说清楚，而不是含糊过去
    const mismatched = candidate({ overlapDimensionCount: 3 });
    expect(signalSummary(mismatched)).toContain('命中 3 条线（逐维度明细 2 条）');
    expect(signalSummary(candidate({ dimensions: [] }))).toBe('无命中');
  });

  it('最高分取逐维度最大值', () => {
    expect(topScore(candidate())).toBe(1);
    const onlyWeaker = candidate({
      similarity: 0.9,
      dimensions: [{ dimension: 'SIMILAR_PRODUCT', label: 'x', score: 0.83, evidence: '' }],
    });
    expect(topScore(onlyWeaker)).toBe(0.83);
    // 逐维度为空时退回整体分（不该发生，但不该返回 0 让页面显示「无命中」）
    expect(topScore(candidate({ dimensions: [], similarity: 0.77 }))).toBe(0.77);
  });
});

describe('影响引用数', () => {
  it('只列非零项并说清全零与未查到', () => {
    expect(referenceSummary(detail().leftReferences)).toBe('条码与物料标识 1 · 素材引用 1');
    expect(referenceSummary([])).toBe('未查到引用');
    expect(referenceSummary(undefined)).toBe('未查到引用');
    const allZero = [
      { dimension: 'QUOTE', label: '供应商报价', count: 0 },
      { dimension: 'MEDIA', label: '素材引用', count: 0 },
    ];
    expect(referenceSummary(allZero)).toBe('无 catalog 侧引用');
  });

  it('引用总数把空值与负值归零', () => {
    expect(referenceTotal(3)).toBe(3);
    expect(referenceTotal(0)).toBe(0);
    expect(referenceTotal(null)).toBe(0);
    expect(referenceTotal(undefined)).toBe(0);
    expect(referenceTotal(-1)).toBe(0);
  });
});

describe('范围收窄必须显式警示', () => {
  it('全量扫描不报警', () => {
    expect(scopeWarning(scan())).toBeNull();
    expect(scopeWarning(null)).toBeNull();
    expect(scopeWarning(undefined)).toBeNull();
  });

  it('收窄过的扫描要说清「0 个候选不等于干净」', () => {
    const scoped = scan({ scopeApplied: true, scopeCategory: '数码', candidateCount: 0 });
    const warning = scopeWarning(scoped);
    expect(warning).toContain('数码');
    expect(warning).toContain('0 个候选不等于干净');
    expect(warning).toContain('全量');
  });

  it('收窄过但有候选时也仍然提示范围', () => {
    const warning = scopeWarning(scan({ scopeApplied: true, scopeBrand: 'Sony', candidateCount: 3 }));
    expect(warning).toContain('Sony');
    expect(warning).not.toBeNull();
  });
});

describe('扫描读数', () => {
  it('耗时翻成人话', () => {
    expect(durationText(420)).toBe('420 毫秒');
    expect(durationText(1500)).toBe('1.5 秒');
    expect(durationText(null)).toBe('—');
    expect(durationText(-1)).toBe('—');
  });

  it('时刻为空或不可解析时说未记录而不是 Invalid Date', () => {
    expect(timeText(null)).toBe('未记录');
    expect(timeText('')).toBe('未记录');
    expect(timeText('不是时间')).toBe('未记录');
    expect(timeText('2026-10-07T08:00:00Z')).not.toBe('未记录');
  });
});

describe('决定', () => {
  const mergeDraft: DecisionDraft = { decision: 'MERGE', primarySkuId: 'sku-left', note: '同一商品' };

  it('按钮文案不能说成已经合并', () => {
    expect(decisionButtonLabel('MERGE')).toBe('登记合并决定');
    expect(decisionButtonLabel('KEEP')).toBe('登记保留决定');
    // 一旦出现「合并」二字的裸按钮，用户会以为数据已经归并了。
    expect(decisionButtonLabel('MERGE')).not.toBe('合并');
  });

  it('MERGE 必须指主记录且必须落在候选两侧之内', () => {
    expect(canSubmitDecision(mergeDraft, detail())).toEqual({ ok: true, reason: '' });
    expect(canSubmitDecision({ ...mergeDraft, primarySkuId: '' }, detail()).ok).toBe(false);
    expect(canSubmitDecision({ ...mergeDraft, primarySkuId: '' }, detail()).reason).toContain('保留哪一条');
    const outside = canSubmitDecision({ ...mergeDraft, primarySkuId: 'sku-elsewhere' }, detail());
    expect(outside.ok).toBe(false);
    expect(outside.reason).toContain('两条之一');
    expect(canSubmitDecision(mergeDraft, null).ok).toBe(false);
    expect(canSubmitDecision(null, detail()).ok).toBe(false);
  });

  it('KEEP 不需要主记录，但理由仍限长', () => {
    expect(canSubmitDecision({ decision: 'KEEP', primarySkuId: '', note: '' }, detail()).ok).toBe(true);
    expect(canSubmitDecision({ decision: 'KEEP', primarySkuId: '', note: 'x'.repeat(1001) }, detail()).ok)
      .toBe(false);
    expect(
      canSubmitDecision({ decision: 'MERGE', primarySkuId: 'sku-left', note: 'x'.repeat(1001) }, detail())
        .ok,
    ).toBe(false);
  });

  it('请求体带 expectedVersion：首次 0，重选带当前版本', () => {
    expect(decisionPayload(mergeDraft, null)).toEqual({
      decision: 'MERGE',
      primarySkuId: 'sku-left',
      note: '同一商品',
      expectedVersion: 0,
    });
    const existing = { version: 3 } as DuplicateDecisionRow;
    expect(decisionPayload(mergeDraft, existing).expectedVersion).toBe(3);
    // KEEP 不带 primarySkuId：带上会让后端以为要执行合并
    const keep = decisionPayload({ decision: 'KEEP', primarySkuId: 'sku-left', note: '' }, existing);
    expect(keep).not.toHaveProperty('primarySkuId');
    expect(keep.decision).toBe('KEEP');
  });

  it('主记录选项只有候选两侧且文案说清保留哪条', () => {
    const options = primaryOptions(detail());
    expect(options).toHaveLength(2);
    // 先断长度再取下标：noUncheckedIndexedAccess 下 options[0] 是 T | undefined，
    // 断言 toHaveLength 不会让 TS 知道它一定存在，所以取值要显式收一下。
    expect(options.map((o) => o.value)).toEqual(['sku-left', 'sku-right']);
    expect(options.map((o) => o.label)).toEqual([
      '保留 L-1（左）',
      '保留 R-1（右）',
    ]);
    expect(primaryOptions(null)).toEqual([]);
  });

  it('登记前后都要说清执行归 3.9.1.2', () => {
    expect(executionHint('MERGE')).toContain('3.9.1.2');
    expect(executionHint('MERGE')).toContain('expected_version');
    expect(executionHint('MERGE')).toContain('不改任何商品数据');
    expect(executionHint('KEEP')).toContain('不改任何商品数据');
    // nextStepHint 由后端给出（它知道这条候选到底是 MERGE 还是 KEEP），前端只负责展示。
    expect(
      detail({
        nextStepHint:
          '本模块只登记决定，不执行合并。判成合并后由 3.9.1.2 执行：它会带着本决定里的 expected_version 回来比对。',
      }).nextStepHint,
    ).toContain('3.9.1.2');
  });
});

describe('阈值', () => {
  it('默认阈值与后端一致', () => {
    expect(DEFAULT_MIN_SIMILARITY).toBe('0.8000');
    expect(validateMinSimilarity(DEFAULT_MIN_SIMILARITY)).toEqual({ ok: true, reason: '', value: 0.8 });
  });

  it('越界与非数字被拒而不是静默夹取', () => {
    expect(validateMinSimilarity('2').ok).toBe(false);
    expect(validateMinSimilarity('2').reason).toContain('0 到 1');
    expect(validateMinSimilarity('-1').ok).toBe(false);
    expect(validateMinSimilarity('abc').ok).toBe(false);
    expect(validateMinSimilarity('').ok).toBe(false);
    // 边界值合法
    expect(validateMinSimilarity('0').ok).toBe(true);
    expect(validateMinSimilarity('1').ok).toBe(true);
  });
});

describe('错误文案', () => {
  it('409 单独提示可能是别人改过决定', () => {
    const conflict = new ApiError(409, '这个候选的决定已被他人修改，请刷新后重试');
    expect(errorText(conflict)).toContain('刷新');
    expect(errorText(conflict)).toContain('别人刚改过');
    // 非 409 给后端原文，不加解释
    expect(errorText(new ApiError(400, '主记录必须是这个候选里的两条之一'))).toBe(
      '主记录必须是这个候选里的两条之一',
    );
    expect(errorText(new Error('普通失败'))).toBe('普通失败');
    expect(errorText('字符串')).toBe('请求失败，请稍后重试');
  });
});