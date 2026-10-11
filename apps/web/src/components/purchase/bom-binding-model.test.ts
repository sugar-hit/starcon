import { describe, expect, it } from 'vitest';
import type { BomBinding, BomSnapshotLine } from '../../../../../packages/api-contracts/src/index';
import {
  depthIndent,
  driftHeadline,
  isPurchaseLine,
  requiredText,
  scrapText,
  snapshotTone,
} from './bom-binding-model';

/**
 * 6.4.1.2 展示口径的单测。
 *
 * <p>这些函数是**口径**，不是格式化工具：损耗率的百分数语义（R6）
 * 与需求量的不取整语义（R7）都靠它们在页面上保持原样。
 * 一旦有人在这里「顺手」除以 100 或 `Math.round`，
 * typecheck / lint / vitest 不会红，而运营看到的领料量会翻 6 倍。
 */

function line(patch: Partial<BomSnapshotLine> = {}): BomSnapshotLine {
  return {
    id: 'line-1',
    parentLineId: null,
    depth: 1,
    path: '1',
    skuId: 'sku-1',
    skuCode: 'FABRIC',
    skuName: '面料',
    componentId: 'comp-1',
    sourceVersionId: 'v-1',
    sourceVersionNo: 'v1',
    sourceBomCode: 'BOM-001',
    unitQuantity: 0.5,
    scrapRate: 5,
    requiredQuantity: 5.25,
    available: 0,
    inTransit: 0,
    gap: 5.25,
    surplus: 0,
    isSubAssembly: false,
    subBomId: null,
    subBomCode: null,
    subVersionId: null,
    subVersionNo: null,
    ambiguousSubBom: false,
    expanded: false,
    covered: false,
    truncated: false,
    role: '',
    note: '',
    ...patch,
  } as BomSnapshotLine;
}

/**
 * 构造一个绑定对象。
 *
 * <p><b>snapshot 在「还没冻结」时是真的 null</b>（后端返回 null，不是缺字段）。
 * 契约里它是裸 `$ref`（Spring 对记录组件引用不发射 nullable，与 6.4.1.1 的
 * inboundRequest 同一形状），所以这里显式断言成 `BomBinding | null` ——
 * 让测试的形状与**运行时**的真实响应一致，而不是迁就契约的单取值。
 */
function binding(patch: Partial<BomBinding> = {}): BomBinding {
  const snapshot = {
    id: 's-1',
    bomVersionNo: 'v1',
    truncated: false,
    outputQuantity: 10,
    lines: [],
  } as unknown as BomBinding['snapshot'];
  return {
    orderId: 'o-1',
    orderNo: 'PO-1',
    orderStatus: 'DRAFT',
    effectiveVersionNo: 'v1',
    drifted: false,
    effectiveVersionMissing: false,
    notes: [],
    ...patch,
    // 用 `'snapshot' in patch` 而不是 `??`：显式传 `null` 表示「还没冻结」，
    // 它是一个**合法状态**，不能被默认快照顶掉（`null ?? x` 会静默取 x）。
    snapshot: 'snapshot' in patch ? (patch.snapshot as BomBinding['snapshot']) : snapshot,
  } as unknown as BomBinding;
}

describe('requiredText：需求量不取整（R7）', () => {
  it('6 位小数原样显示，只去掉无意义的尾随零', () => {
    expect(requiredText(5.25)).toBe('5.25');
    expect(requiredText(10.0000001)).toBe('10');
    expect(requiredText(0.500000)).toBe('0.5');
  });

  it('不取整：小数必须留到第 6 位', () => {
    // 取整成整数是 3.5.2.2 与 06 采购建议的职责，页面不替它们做决定。
    expect(requiredText(31.512345)).toBe('31.512345');
    expect(requiredText(31.5123456)).toBe('31.512346');
  });

  it('缺失值显示为破折号而不是 0', () => {
    // 0 是一个有业务含义的数（「缺口 0」）；把「没有这个数」显示成 0 会误导。
    expect(requiredText(null)).toBe('—');
    expect(requiredText(undefined)).toBe('—');
    expect(requiredText('')).toBe('—');
  });

  it('字符串形态的数值走同一条口径', () => {
    expect(requiredText('5.250000')).toBe('5.25');
  });
});

describe('scrapText：损耗率是百分数不是比率（R6）', () => {
  it('带 % 显示，且数值原样保留', () => {
    // 5 = 损耗 5%。除以 100 变成 0.05 就是 R6 明确禁止的比率化，
    // 它会让「填 5」被读成 500% 损耗且不报错。
    expect(scrapText(5)).toBe('5%');
    expect(scrapText(0)).toBe('0%');
    expect(scrapText(0.5)).toBe('0.5%');
    expect(scrapText(100)).toBe('100%');
  });

  it('缺失值按 0%（无损耗）处理，不留空让人猜', () => {
    expect(scrapText(null)).toBe('0%');
    expect(scrapText(undefined)).toBe('0%');
  });
});

describe('展开树形态', () => {
  it('缩进按 depth，第 1 层不缩进', () => {
    expect(depthIndent(line({ depth: 1 }))).toBe(0);
    expect(depthIndent(line({ depth: 2 }))).toBe(1);
    expect(depthIndent(line({ depth: 3 }))).toBe(2);
  });

  it('「需采购」= 缺口 > 0 且没有再往下展开（E2）', () => {
    expect(isPurchaseLine(line({ gap: 5.25, expanded: false }))).toBe(true);
    // 中间层不是采购对象：它是过程。
    expect(isPurchaseLine(line({ gap: 5.25, expanded: true }))).toBe(false);
    expect(isPurchaseLine(line({ gap: 0, expanded: false }))).toBe(false);
  });
});

describe('冻结状态与漂移的展示口径', () => {
  it('截断必须是 danger，完整是 success，没有快照是 neutral', () => {
    // 静默显示一份不完整的清单比不显示更危险 —— 页面必须显红。
    expect(snapshotTone(binding({ snapshot: null } as unknown as Partial<BomBinding>).snapshot)).toBe(
      'neutral',
    );
    expect(snapshotTone(binding().snapshot)).toBe('success');
    expect(snapshotTone({ ...(binding().snapshot as object), truncated: true } as BomBinding['snapshot'])).toBe(
      'danger',
    );
  });

  it('漂移时说出「冻结的是哪一版、此刻生效的是哪一版」', () => {
    const b = binding({ drifted: true, effectiveVersionNo: 'v2' });
    const headline = driftHeadline(b);
    expect(headline).toContain('v1');
    expect(headline).toContain('v2');
    // 措辞必须点明冻结仍然有效 —— 否则运营会以为冻结失效了。
    expect(headline).toContain('接替');
  });

  it('没有漂移时不显示告警标题（正常状态不该刷屏）', () => {
    expect(driftHeadline(binding())).toBeNull();
    expect(driftHeadline(binding({ effectiveVersionMissing: true }))).toContain('没有生效版本');
  });
});