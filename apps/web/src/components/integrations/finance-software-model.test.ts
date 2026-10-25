import { describe, expect, it } from 'vitest';
import type {
  FinanceSoftwareCapabilities,
  FinanceSoftwareReconcileDiffRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  accountStatusTone,
  canPush,
  capabilityTone,
  directionLabel,
  diffExternalCodeLabel,
  downloadName,
  exportSummary,
  formatAmount,
  formatMoney,
  LOCAL_ONLY_PREFIX,
  pushCapability,
  reconcileReasonLabel,
  reconcileReasonTone,
  reconcileStatusTone,
  shortSha,
  sortDiffs,
  unmappedTone,
  unavailableHint,
} from './finance-software-model';

/**
 * 18.2.1.1 财务软件 前端模型的单测。
 *
 * 🔴 这组用例守的是五件最容易做丢的事：
 *
 * 1. **未接入的能力不能显示成「可用」** —— `canPush` 只在后端明确声明
 *    `available=true` 时才为真；「后端没声明」与「声明了但未接入」都必须是 false。
 * 2. **占位符不能原样甩给人看** —— `LOCAL:6602` 要显示成「无外部科目（本地独有）」，
 *    否则财务会以为那是对方系统里的一个真实科目编码。
 * 3. **未映射行数必须出现在摘要里** —— 那是「这份文件不完整」的唯一信号。
 * 4. **差异排序是钱差在前** —— 「哪条金额对不上」比「哪条只是缺一个科目」重要。
 * 5. **金额只做显示，不做记账** —— `formatAmount` 不许吐出 `1.3e+4` 这种财务看不懂的样子，
 *    也不许把 0 显示成 `-0`。
 */

const capability = (over: Partial<FinanceSoftwareCapabilities> = {}): FinanceSoftwareCapabilities => ({
  capabilities: [
    {
      moduleId: '18.2.1.1',
      name: '科目映射（本系统科目 ↔ 外部科目）',
      available: true,
      evidence: 'platform_finance_subject_map 表 + 映射端点',
      ownerModule: '',
      note: '',
    },
    {
      moduleId: '18.2.1.1',
      name: '**直连推送凭证到外部财务软件**',
      available: false,
      evidence: '本仓目前没有任何真实的财务软件适配器',
      ownerModule: '18.2',
      note: '推送端点如实答 409 且一次数据都不写；在此之前走「导出凭证文件 → 导入对方系统」这条完整路径',
    },
  ],
  gaps: [],
  ...over,
});

const diff = (over: Partial<FinanceSoftwareReconcileDiffRow>): FinanceSoftwareReconcileDiffRow => ({
  id: '00000000-0000-4000-8000-000000000001',
  taskId: '00000000-0000-4000-8000-000000000002',
  externalSubjectCode: 'EX-AR-1122',
  externalSubjectName: '外部应收',
  localSubjectCode: '1122',
  localSubjectName: '应收账款',
  currency: 'USD',
  externalBalance: 1000,
  localBalance: 900,
  difference: 100,
  reason: 'BALANCE_DIFF',
  reasonLabel: '金额不等',
  detail: '',
  createdAt: '2026-10-25T00:00:00Z',
  ...over,
});

describe('18.2.1.1 财务软件 · 能力声明', () => {
  it('未接入的直连推送必须显示成未接入而不是可用', () => {
    const caps = capability();
    expect(canPush(caps)).toBe(false);
    const declared = pushCapability(caps);
    expect(declared?.available).toBe(false);
    expect(declared?.ownerModule).toBe('18.2');
  });

  it('后端没声明这一维时状态是未知而不是已接入', () => {
    expect(canPush(null)).toBe(false);
    expect(canPush({ capabilities: [], gaps: [] })).toBe(false);
    expect(pushCapability(null)).toBeNull();
  });

  it('后端明确声明可用时才提供推送动作', () => {
    const caps = capability({
      capabilities: [
        {
          moduleId: '18.2.1.1',
          name: '**直连推送凭证到外部财务软件**',
          available: true,
          evidence: '金蝶适配器',
          ownerModule: '',
          note: '',
        },
      ],
    });
    expect(canPush(caps)).toBe(true);
  });

  it('未接入的那一格要写明缺在谁身上以及现在怎么办', () => {
    const hint = unavailableHint(pushCapability(capability()));
    expect(hint).toContain('未接入');
    expect(hint).toContain('18.2');
    // 关键是给出**现在怎么办**的路，而不是只说一句「没有」。
    expect(hint).toContain('导出凭证文件');
  });

  it('后端没声明时提示是未知而不是未接入', () => {
    expect(unavailableHint(null)).toContain('未知');
  });

  it('能力声明的语气区分可用与未接入', () => {
    expect(capabilityTone(true)).toBe('ok');
    expect(capabilityTone(false)).toBe('muted');
  });
});

describe('18.2.1.1 财务软件 · 差异展示口径', () => {
  it('占位符不能原样显示成外部科目编码', () => {
    const label = diffExternalCodeLabel(`${LOCAL_ONLY_PREFIX}6602`);
    expect(label.placeholder).toBe(true);
    expect(label.label).toBe('无外部科目（本地独有）');
    expect(label.label).not.toContain('6602');
  });

  it('真实的外部科目编码原样显示', () => {
    const label = diffExternalCodeLabel('EX-AR-1122');
    expect(label.placeholder).toBe(false);
    expect(label.label).toBe('EX-AR-1122');
  });

  it('空编码显示成占位而不是空白', () => {
    expect(diffExternalCodeLabel('').placeholder).toBe(true);
  });

  it('四种差异原因各有说法', () => {
    expect(reconcileReasonLabel('BALANCE_DIFF')).toBe('金额不等');
    expect(reconcileReasonLabel('ONLY_LOCAL')).toBe('仅本地有');
    expect(reconcileReasonLabel('ONLY_EXTERNAL')).toBe('仅外部有');
    expect(reconcileReasonLabel('CURRENCY_MISMATCH')).toBe('币种不一致');
  });

  it('币种不一致是黄的其余差异是红的', () => {
    expect(reconcileReasonTone('CURRENCY_MISMATCH')).toBe('warn');
    expect(reconcileReasonTone('BALANCE_DIFF')).toBe('danger');
    expect(reconcileReasonTone('ONLY_LOCAL')).toBe('danger');
    expect(reconcileReasonTone('ONLY_EXTERNAL')).toBe('danger');
  });

  it('钱差排在最前面', () => {
    const sorted = sortDiffs([
      diff({ reason: 'CURRENCY_MISMATCH', externalSubjectCode: 'EX-C' }),
      diff({ reason: 'ONLY_LOCAL', externalSubjectCode: 'EX-B' }),
      diff({ reason: 'BALANCE_DIFF', externalSubjectCode: 'EX-A' }),
    ]);
    expect(sorted.map((d) => d.reason)).toEqual([
      'BALANCE_DIFF',
      'ONLY_LOCAL',
      'CURRENCY_MISMATCH',
    ]);
  });

  it('排序不改动原数组', () => {
    const original = [diff({ reason: 'ONLY_LOCAL' }), diff({ reason: 'BALANCE_DIFF' })];
    const copy = [...original];
    sortDiffs(original);
    expect(original).toEqual(copy);
  });

  it('方向的说法是借方贷方', () => {
    expect(directionLabel('DEBIT')).toBe('借方');
    expect(directionLabel('CREDIT')).toBe('贷方');
    expect(directionLabel('')).toBe('—');
  });
});

describe('18.2.1.1 财务软件 · 金额只做显示', () => {
  it('不吐科学计数法', () => {
    expect(formatAmount(13000)).toBe('13000');
    expect(formatAmount(13000000000)).toBe('13000000000');
  });

  it('去掉尾随零但不丢精度', () => {
    expect(formatAmount(1300.5)).toBe('1300.5');
    expect(formatAmount(0.000001)).toBe('0.000001');
    expect(formatAmount(100)).toBe('100');
  });

  it('零显示成 0 且没有 -0', () => {
    expect(formatAmount(0)).toBe('0');
    expect(formatAmount(-0)).toBe('0');
  });

  it('空值显示成破折号而不是 0', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount(Number.NaN)).toBe('—');
  });

  it('负数保留符号', () => {
    expect(formatAmount(-300)).toBe('-300');
  });

  it('带币种时把币种拼在后面', () => {
    expect(formatMoney(1300, 'USD')).toBe('1300 USD');
    expect(formatMoney(null, 'USD')).toBe('—');
  });
});

describe('18.2.1.1 财务软件 · 导出摘要', () => {
  it('未映射行数必须出现在摘要里', () => {
    const summary = exportSummary({ voucherCount: 2, lineCount: 4, unmappedLineCount: 1 });
    expect(summary).toContain('2 张凭证');
    expect(summary).toContain('4 行分录');
    expect(summary).toContain('1 行未配科目映射');
  });

  it('全部映射时摘要不含未映射字样', () => {
    const summary = exportSummary({ voucherCount: 1, lineCount: 2, unmappedLineCount: 0 });
    expect(summary).toContain('全部已映射');
    expect(summary).not.toContain('未配科目映射');
  });

  it('有未映射行是黄的全部映射是绿', () => {
    expect(unmappedTone(0)).toBe('ok');
    expect(unmappedTone(1)).toBe('warn');
  });

  it('sha256 只显示前 12 位', () => {
    expect(shortSha('a'.repeat(64))).toBe(`${'a'.repeat(12)}…`);
    expect(shortSha('')).toBe('—');
  });

  it('下载名用后端给的文件名', () => {
    expect(downloadName({ filename: 'vouchers-BOOK-001.csv' })).toBe('vouchers-BOOK-001.csv');
    expect(downloadName({ fileNo: 'FS-ABCD1234-000001' })).toBe('FS-ABCD1234-000001');
  });
});

describe('18.2.1.1 财务软件 · 状态语气', () => {
  it('停用的连接档案是灰的启用的是绿的', () => {
    expect(accountStatusTone('ACTIVE')).toBe('ok');
    expect(accountStatusTone('DISABLED')).toBe('muted');
  });

  it('算完的核对任务是绿的待计算是灰的', () => {
    expect(reconcileStatusTone('COMPUTED')).toBe('ok');
    expect(reconcileStatusTone('DRAFT')).toBe('muted');
  });
});
