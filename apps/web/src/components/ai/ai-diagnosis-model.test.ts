import { describe, expect, it } from 'vitest';
import type {
  AiDiagnosisConclusionDetail,
  AiDiagnosisEvidenceRow,
  AiDiagnosisRunRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  anomalyLabel,
  conclusionRows,
  evidenceLevelLabel,
  evidenceLevelTone,
  evidenceRows,
  executionBoundaryNotice,
  factServiceLabel,
  formatValue,
  runReadState,
  severityLabel,
  severityTone,
  sourceLabel,
  statusLabel,
  statusTone,
  unavailableNote,
} from './ai-diagnosis-model';

/**
 * 18.4.2.3 异常诊断 前端模型的单测。
 *
 * 🔴 这组用例守的是五件最容易做丢的事：
 *
 * 1. **依据必须摊得开** —— 事实表 / 字段 / 数值 / 快照时间 / 下钻引用 / 口径说明，
 *    一项都不能少（蓝图加粗句「诊断要给依据」）。
 * 2. **「读不到」与「没有结论」是两件事** —— `runReadState` 必须把这两种状态分开，
 *    混成一句「暂无结论」就是把「没查出来」说成「没问题」。
 * 3. **依据硬度不是置信度** —— 只能说 STRONG / WEAK / INSUFFICIENT，
 *    不写「置信度 87%」这种本仓没有来源的数。
 * 4. **未接通 ≠ 没有数据** —— `unavailableNote` 必须写明「未接通」与归谁。
 * 5. **仅诊断，不执行** —— 边界提示里必须点名「不执行」与归谁执行。
 */

const evidence = (over: Partial<AiDiagnosisEvidenceRow>): AiDiagnosisEvidenceRow => ({
  id: '00000000-0000-4000-8000-000000000001',
  conclusionId: '00000000-0000-4000-8000-000000000002',
  factService: 'warehouse',
  factTable: 'inventory_balance',
  factField: 'available',
  factValue: 0,
  factValueText: '',
  factUnit: '件',
  factRef: 'bal-1',
  factOccurredAt: null,
  snapshotAt: '2026-11-02T00:00:00Z',
  explanation: '7 库存总览行的 available 是生成列（在手 − 预占），快照时点读到。',
  createdAt: '2026-11-02T00:00:00Z',
  ...over,
});

const conclusion = (over: Partial<AiDiagnosisConclusionDetail>): AiDiagnosisConclusionDetail => ({
  conclusion: {
    id: '00000000-0000-4000-8000-000000000002',
    runId: '00000000-0000-4000-8000-000000000003',
    eventId: '00000000-0000-4000-8000-000000000004',
    ruleCode: 'INVENTORY_STOCKOUT',
    ruleLabel: '可用库存归零',
    anomalyType: 'INVENTORY',
    severity: 'CRITICAL',
    subjectRef: 'SKU-DIAG',
    summary: 'SKU SKU-DIAG 的可用库存为 0 件',
    detail: '可用量 = 在手 0 − 预占 0 = 0 件。',
    evidenceLevel: 'WEAK',
    impact: 0,
    impactUnit: '件',
    impactText: '-100.00%',
    recommendation: '核对 7 库存明细里的库位与质量状态。',
    actionRef: 'GET /warehouse/inventory/overview',
    actionOwner: '7 库存',
    createdAt: '2026-11-02T00:00:00Z',
  },
  evidences: [evidence({})],
  ...over,
});

const run = (over: Partial<AiDiagnosisRunRow>): AiDiagnosisRunRow => ({
  id: '00000000-0000-4000-8000-000000000003',
  runNo: 'DG-ABCD1234-000001',
  eventId: '00000000-0000-4000-8000-000000000004',
  status: 'COMPLETED',
  ruleVersion: 'v1',
  conclusionCount: 1,
  evidenceCount: 1,
  sourcesRead: 'warehouse,reporting',
  sourcesUnavailable: '',
  note: '命中 1 条归因规则，产出 1 条依据',
  requestedBy: '00000000-0000-4000-8000-000000000005',
  startedAt: '2026-11-02T00:00:00Z',
  finishedAt: '2026-11-02T00:00:05Z',
  ...over,
});

describe('18.4.2.3 依据（一等公民）', () => {
  it('每条依据都摊开事实表、字段、数值、快照时间与下钻引用', () => {
    const rows = evidenceRows([evidence({})]);
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.table).toBe('inventory_balance');
    expect(row.field).toBe('available');
    expect(row.value).toBe('0');
    expect(row.unit).toBe('件');
    expect(row.ref).toBe('bal-1');
    expect(row.snapshotAt).not.toBe('—');
    expect(row.explanation).not.toBe('');
  });

  it('文字型取值走 factValueText，数值型走 factValue', () => {
    const rows = evidenceRows([
      evidence({ factValue: null, factValueText: 'OVERDUE', factField: 'ship_sla_state' }),
    ]);
    expect(rows[0]!.value).toBe('OVERDUE');
  });

  it('依据为空时摊开成空数组，不编一条出来', () => {
    expect(evidenceRows([])).toEqual([]);
    expect(evidenceRows(undefined as never)).toEqual([]);
  });
});

describe('18.4.2.3 结论', () => {
  it('结论带上依据条数、建议、归谁与处理页', () => {
    const rows = conclusionRows([conclusion({})]);
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.ruleCode).toBe('INVENTORY_STOCKOUT');
    expect(row.evidenceCount).toBe(1);
    expect(row.recommendation).not.toBe('');
    expect(row.actionOwner).toBe('7 库存');
    expect(row.actionRef).not.toBe('');
  });

  it('影响幅度与带符号百分比分开呈现 —— 只显示一个会把涨说成跌', () => {
    const rows = conclusionRows([
      conclusion({
        conclusion: {
          ...conclusion({}).conclusion,
          impact: 2.5,
          impactUnit: 'USD/件',
          impactText: '-40.00%',
        },
      }),
    ]);
    expect(rows[0]!.impact).toBe('2.5');
    expect(rows[0]!.impactText).toBe('-40.00%');
  });

  it('依据硬度说的是「依据有多硬」，不是置信度', () => {
    expect(evidenceLevelLabel('STRONG')).toContain('依据充分');
    expect(evidenceLevelLabel('WEAK')).toContain('依据较弱');
    expect(evidenceLevelLabel('INSUFFICIENT')).toContain('依据不足');
    // 🔴 任何一档都**不得**出现「置信度」这种本仓没有来源的说法。
    for (const level of ['STRONG', 'WEAK', 'INSUFFICIENT']) {
      expect(evidenceLevelLabel(level)).not.toContain('置信度');
    }
    expect(evidenceLevelTone('STRONG')).toBe('ok');
    expect(evidenceLevelTone('WEAK')).toBe('warn');
    expect(evidenceLevelTone('INSUFFICIENT')).toBe('muted');
  });
});

describe('18.4.2.3 「读不到」与「没有结论」分开', () => {
  it('还没跑过诊断：写明「没诊断过不等于没问题」', () => {
    const state = runReadState(null, 0);
    expect(state.title).toContain('还没有跑过诊断');
    expect(state.detail).toContain('不等于没问题');
  });

  it('读不到事实源：写明哪几格没读到，而不是说「没有结论」', () => {
    const state = runReadState(run({ sourcesUnavailable: 'warehouse(连不上)' }), 0);
    expect(state.tone).toBe('danger');
    expect(state.title).toContain('读不到');
    expect(state.detail).toContain('warehouse');
    // 🔴 不得说成「没有命中规则」—— 那是另一件事。
    expect(state.title).not.toContain('没有命中');
  });

  it('读到了但没命中规则：这才是「没有结论」', () => {
    const state = runReadState(run({ conclusionCount: 0 }), 0);
    expect(state.title).toContain('没有命中');
    expect(state.tone).toBe('warn');
  });

  it('正常产出结论', () => {
    const state = runReadState(run({}), 2);
    expect(state.tone).toBe('ok');
    expect(state.title).toContain('2 条结论');
  });
});

describe('18.4.2.3 未接通与边界', () => {
  it('未接通的项写明「未接通」与归谁，不显示成「暂无」', () => {
    const note = unavailableNote('18.4', '本仓没有任何 LLM 适配器');
    expect(note).toContain('未接通');
    expect(note).toContain('18.4');
    expect(note).not.toBe('暂无');
  });

  it('边界提示写明「不执行」与各动作归谁', () => {
    const notice = executionBoundaryNotice();
    expect(notice).toContain('不执行');
    expect(notice).toContain('6 采购');
    expect(notice).toContain('5 定价');
    expect(notice).toContain('14 广告');
  });
});

describe('18.4.2.3 展示口径', () => {
  it('五类异常与状态的中文说法', () => {
    expect(anomalyLabel('INVENTORY')).toBe('库存异常');
    expect(anomalyLabel('PROFIT')).toBe('利润异常');
    expect(anomalyLabel('SHIPPING_DELAY')).toBe('发货延迟');
    expect(anomalyLabel('SYNC_FAILURE')).toBe('同步失败');
    expect(anomalyLabel('AD_LOSS')).toBe('广告亏损');
    expect(statusLabel('OPEN')).toBe('待诊断');
    expect(statusLabel('DIAGNOSED')).toBe('已诊断');
    expect(statusLabel('RESOLVED')).toBe('已处置');
  });

  it('严重度、状态与来源的语气分档', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warn');
    expect(severityTone('INFO')).toBe('muted');
    expect(statusTone('OPEN')).toBe('danger');
    expect(statusTone('RESOLVED')).toBe('ok');
    expect(severityLabel('CRITICAL')).toBe('严重');
    expect(sourceLabel('SUBMITTED')).toBe('人工登记');
    expect(sourceLabel('PLATFORM_INTERNAL')).toBe('平台内部失败源');
  });

  it('事实服务标出它属于哪个系统', () => {
    expect(factServiceLabel('warehouse')).toContain('库存');
    expect(factServiceLabel('reporting')).toContain('分析');
    expect(factServiceLabel('platform')).toContain('平台');
  });

  it('空值显示成「—」，不显示成 0', () => {
    expect(formatValue(null)).toBe('—');
    expect(formatValue(undefined)).toBe('—');
    expect(formatValue('')).toBe('—');
    expect(formatValue(0)).toBe('0');
  });
});
