import { describe, expect, it } from 'vitest';
import type {
  ApprovalTodoAction,
  ApprovalTodoBucket,
  ApprovalTodoCapabilities,
  ApprovalTodoFilterCapability,
  ApprovalTodoItem,
} from '../../../../../packages/api-contracts/src/index';
import {
  APPROVAL_DETAIL_ROUTE,
  APPROVAL_SORT_KEYS,
  EMPTY_FILTERS,
  actionTarget,
  availableActions,
  blockedActions,
  bucketCount,
  bucketLabel,
  buildQuery,
  formatAmount,
  formatDwell,
  formatThreshold,
  instanceNoDigest,
  nodeLabel,
  parseAmount,
  timeoutLabel,
  timeoutState,
  unavailableFilters,
} from './approval-todo-model';

/**
 * 1.1.1.3 审批待办的**展示口径**单测。
 *
 * <p>本模块最容易做错的三件事，逐条钉在这里：
 *
 * <ol>
 *   <li><b>「判不了」被当成「没有」</b>：节点没配时限时，它既不是「已超时」也不是「没超时」；
 *       这一档必须独立存在，不能悄悄按「没超时」渲染。</li>
 *   <li><b>上界写成闭区间</b>：后端的金额区间上界是**开区间**，
 *       页面上写成「≤」会让人以为边界值落在这一档，而它其实落在下一档。</li>
 *   <li><b>空值被当成筛选条件</b>：空串下发到后端会变成「筛这个空值」，
 *       于是「全部」变成了「一个都没有」。</li>
 * </ol>
 */

const INSTANCE_ID = '22222222-2222-2222-2222-222222222222';

function item(overrides: Partial<ApprovalTodoItem> = {}): ApprovalTodoItem {
  return {
    id: INSTANCE_ID,
    instanceNo: 'AP-000000000001',
    documentTypeCode: 'PURCHASE_ORDER',
    documentTypeName: '采购单',
    flowName: '采购审批流',
    subjectTitle: '采购单 A',
    amount: 500,
    currencyCode: 'USD',
    applicantId: '33333333-3333-3333-3333-333333333333',
    applicantName: '张三',
    currentNodeKey: 'R1',
    currentNodeName: '财务复核',
    currentNodeOrder: 0,
    currentStageIndex: 0,
    approverType: 'ROLE',
    sourceSystem: 'trade',
    sourceReferenceId: 'PO-1',
    submittedAt: '2026-10-20T00:00:00Z',
    nodeEnteredAt: '2026-10-21T00:00:00Z',
    dwellSeconds: 3600,
    totalSeconds: 7200,
    timeoutSeconds: 86400,
    overdueSeconds: 0,
    amountThresholdName: null,
    amountThresholdMin: null,
    amountThresholdMax: null,
    amountThresholdCurrency: null,
    status: 'PENDING',
    ...overrides,
  } as ApprovalTodoItem;
}

function bucket(code: string, count: number): ApprovalTodoBucket {
  return { code, name: `名称 ${code}`, count };
}

function filterCapability(
  key: string,
  available: boolean,
  reason: string | null = null,
): ApprovalTodoFilterCapability {
  return { key, label: `维度 ${key}`, available, reason, source: `source ${key}` };
}

function action(code: string, available: boolean, target: string | null = null): ApprovalTodoAction {
  return {
    code,
    label: `动作 ${code}`,
    available,
    ownerModule: available ? '1.1.1.3' : '17.4.2.1',
    reason: available ? null : '审批动作在 17 执行，工作台不重复实现',
    target,
  };
}

function capabilities(
  filters: ApprovalTodoFilterCapability[] = [],
  actions: ApprovalTodoAction[] = [],
): ApprovalTodoCapabilities {
  return {
    module: '1.1.1.3',
    ownerModule: '17.4.2.1',
    dataSource: 'approval_instance（只读投影）',
    filters,
    actions,
  };
}

describe('超时三态：判不了不等于没超时', () => {
  it('节点配了时限且已超出 → OVERDUE', () => {
    const row = item({ timeoutSeconds: 3600, overdueSeconds: 7200 });
    expect(timeoutState(row)).toBe('OVERDUE');
    expect(timeoutLabel(row)).toContain('已超时');
  });

  it('节点配了时限但没超出 → WITHIN', () => {
    const row = item({ timeoutSeconds: 86400, overdueSeconds: 0 });
    expect(timeoutState(row)).toBe('WITHIN');
    expect(timeoutLabel(row)).toContain('时限');
  });

  it('节点没配时限 → UNKNOWN（不是「没超时」）', () => {
    const row = item({ timeoutSeconds: null, overdueSeconds: null });
    expect(timeoutState(row)).toBe('UNKNOWN');
    expect(timeoutLabel(row)).toContain('判不了');
    expect(timeoutLabel(row)).not.toContain('没超时');
  });

  it('overdueSeconds 为 0 但配了时限时不算超时', () => {
    expect(timeoutState(item({ timeoutSeconds: 3600, overdueSeconds: 0 }))).toBe('WITHIN');
  });
});

describe('停留时长与金额文案', () => {
  it('停留时长按秒换算', () => {
    expect(formatDwell(30)).toBe('不到 1 分钟');
    expect(formatDwell(3600)).toBe('1 小时');
    expect(formatDwell(5400)).toBe('1 小时 30 分钟');
    expect(formatDwell(86400)).toBe('1 天');
    expect(formatDwell(90000)).toBe('1 天 1 小时');
  });

  it('非法停留时长报「—」而不是 NaN', () => {
    expect(formatDwell(Number.NaN)).toBe('—');
    expect(formatDwell(-1)).toBe('—');
  });

  it('金额带币种；没有金额报「—」', () => {
    expect(formatAmount('USD', 1234.5)).toBe('USD 1234.50');
    expect(formatAmount('USD', null)).toBe('—');
    expect(formatAmount(null, 10)).toBe('10.00');
  });
});

describe('金额阈值区间：上界是开区间', () => {
  it('上下界都有 → 上界写成 < 而不是 ≤', () => {
    const row = item({
      amountThresholdName: '小额档',
      amountThresholdMin: 0,
      amountThresholdMax: 1000,
      amountThresholdCurrency: 'USD',
    });
    const text = formatThreshold(row);
    expect(text).toContain('小额档');
    expect(text).toMatch(/< ?1000/);
    expect(text).not.toContain('≤');
  });

  it('只有上界 → 不含下界', () => {
    const row = item({
      amountThresholdName: '大额档',
      amountThresholdMin: null,
      amountThresholdMax: 5000,
      amountThresholdCurrency: 'USD',
    });
    expect(formatThreshold(row)).toMatch(/< ?5000/);
  });

  it('只有下界 → 含下界', () => {
    const row = item({
      amountThresholdName: '大额档',
      amountThresholdMin: 1000,
      amountThresholdMax: null,
      amountThresholdCurrency: 'USD',
    });
    expect(formatThreshold(row)).toMatch(/≥ ?1000/);
  });

  it('没有档位 → null（不是「—」，也不是「不限金额」）', () => {
    expect(formatThreshold(item())).toBeNull();
  });
});

describe('能力声明：判不了的维度必须单列', () => {
  it('available=false 的筛选维度被如实列出并带原因', () => {
    const caps = capabilities([
      filterCapability('DOCUMENT_TYPE', true),
      filterCapability('LEGAL_ENTITY_AMOUNT_THRESHOLD', false, '阈值未配置：legal_entity 没有金额阈值列'),
    ]);
    const blocked = unavailableFilters(caps);
    expect(blocked).toHaveLength(1);
    expect(blocked[0]?.key).toBe('LEGAL_ENTITY_AMOUNT_THRESHOLD');
    expect(blocked[0]?.reason).toContain('阈值未配置');
  });

  it('审批动作一律不可用（归 17），跳转与复制可用', () => {
    const caps = capabilities([], [
      action('OPEN_APPROVAL_DETAIL', true, APPROVAL_DETAIL_ROUTE),
      action('COPY_INSTANCE_NO', true),
      action('APPROVE', false),
      action('REJECT', false),
      action('TRANSFER', false),
    ]);
    expect(availableActions(caps).map((a) => a.code)).toEqual([
      'OPEN_APPROVAL_DETAIL',
      'COPY_INSTANCE_NO',
    ]);
    expect(blockedActions(caps).map((a) => a.code)).toEqual(['APPROVE', 'REJECT', 'TRANSFER']);
    for (const blocked of blockedActions(caps)) {
      expect(blocked.ownerModule).toBe('17.4.2.1');
    }
  });

  it('不可用的动作没有前端落点（不会被渲染成能点的按钮）', () => {
    const caps = capabilities([], [action('APPROVE', false, '/org/my-approvals')]);
    expect(actionTarget(caps.actions[0]!)).toBe('');
  });

  it('跳转动作落到 17 的审批详情页', () => {
    const caps = capabilities([], [action('OPEN_APPROVAL_DETAIL', true, APPROVAL_DETAIL_ROUTE)]);
    expect(actionTarget(caps.actions[0]!)).toBe(APPROVAL_DETAIL_ROUTE);
  });

  it('没有能力声明时返回空数组而不是抛错', () => {
    expect(unavailableFilters(null)).toEqual([]);
    expect(availableActions(null)).toEqual([]);
    expect(blockedActions(null)).toEqual([]);
  });
});

describe('筛选条件 → 查询串', () => {
  it('空值一律不下发（「全部」不能变成「筛空值」）', () => {
    expect(buildQuery(EMPTY_FILTERS)).toBe('sort=WAITING');
  });

  it('非空条件逐个下发，币种转大写', () => {
    const query = buildQuery({
      ...EMPTY_FILTERS,
      documentTypeCode: ' PURCHASE_ORDER ',
      sourceSystem: 'trade',
      currencyCode: 'usd',
      amountBranchName: '小额档',
    });
    const parts = new URLSearchParams(query);
    expect(parts.get('documentTypeCode')).toBe('PURCHASE_ORDER');
    expect(parts.get('sourceSystem')).toBe('trade');
    expect(parts.get('currencyCode')).toBe('USD');
    expect(parts.get('amountBranchName')).toBe('小额档');
  });

  it('金额区间与停留下限定量下发', () => {
    const parts = new URLSearchParams(
      buildQuery({ ...EMPTY_FILTERS, minAmount: '100', maxAmount: '2000', minDwellSeconds: '3600' }),
    );
    expect(parts.get('minAmount')).toBe('100');
    expect(parts.get('maxAmount')).toBe('2000');
    expect(parts.get('minDwellSeconds')).toBe('3600');
  });

  it('只看已超时会下发 overdueOnly=true；关掉时不下发', () => {
    expect(new URLSearchParams(buildQuery({ ...EMPTY_FILTERS, overdueOnly: true })).get('overdueOnly')).toBe(
      'true',
    );
    expect(new URLSearchParams(buildQuery(EMPTY_FILTERS)).get('overdueOnly')).toBeNull();
  });
});

describe('输入解析：非法值一律当成不筛', () => {
  it('空串 / 非法字符 / 负数都返回 null', () => {
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('   ')).toBeNull();
    expect(parseAmount('abc')).toBeNull();
    expect(parseAmount('-5')).toBeNull();
    expect(parseAmount('1.5')).toBe(1.5);
    expect(parseAmount(' 42 ')).toBe(42);
  });

  it('非法输入不会被悄悄改写成一个「看起来合理」的值', () => {
    // -5 不解析成 5：那会把「下限填错」变成「按 5 筛」，页面显示的条件与生效的条件不一致
    const parts = new URLSearchParams(buildQuery({ ...EMPTY_FILTERS, minAmount: '-5' }));
    expect(parts.get('minAmount')).toBeNull();
  });
});

describe('分档计数', () => {
  it('取到那一档的条数', () => {
    const buckets = [bucket('PURCHASE_ORDER', 3), bucket('PURCHASE_PAYMENT', 1)];
    expect(bucketCount(buckets, 'PURCHASE_ORDER')).toBe(3);
    expect(bucketLabel(buckets, 'PURCHASE_PAYMENT')).toBe('名称 PURCHASE_PAYMENT');
  });

  it('没有这一档时是 0 而不是报错', () => {
    expect(bucketCount([], 'X')).toBe(0);
    expect(bucketCount(undefined, 'X')).toBe(0);
    expect(bucketLabel(undefined, 'X')).toBe('X');
  });
});

describe('节点与交接清单', () => {
  it('当前节点展示链路位置与审批人类型', () => {
    const text = nodeLabel(item({ currentStageIndex: 1, currentNodeOrder: 2, approverType: 'USER' }));
    expect(text).toContain('第 2 段');
    expect(text).toContain('第 3 个节点');
    expect(text).toContain('指定人');
  });

  it('复制的单号清单只取实例号', () => {
    expect(instanceNoDigest([item(), item({ id: 'x', instanceNo: 'AP-000000000002' })])).toBe(
      'AP-000000000001, AP-000000000002',
    );
  });
});

describe('排序键白名单与后端一致', () => {
  it('白名单里的键都是后端 orderBy 认识的', () => {
    expect(APPROVAL_SORT_KEYS).toContain('WAITING');
    expect(APPROVAL_SORT_KEYS).toContain('OVERDUE');
    expect(APPROVAL_SORT_KEYS).toHaveLength(7);
  });
});
