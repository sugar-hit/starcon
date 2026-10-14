import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  BUNDLE_STATUSES,
  FROZEN_FIELDS,
  MAX_COMPONENTS_PER_VERSION,
  QUANTITY_SCALE,
  VERSION_STATUSES,
  canCloseVersion,
  canEditVersion,
  closeHint,
  closeToProblem,
  closeVersion,
  codeProblem,
  componentCountProblem,
  componentSummary,
  componentsProblem,
  effectiveLabel,
  emptyBundleInput,
  emptyVersionInput,
  errorText,
  instantToLocalInput,
  localInputToInstant,
  noteProblem,
  publishVersion,
  quantityProblem,
  rangeOrderProblem,
  rangeText,
  replaceComponents,
  statusHint,
  statusLabel,
  statusTone,
  toComponentInput,
  toVersionInput,
  versionNameProblem,
  versionNoProblem,
} from './bundle-model';
import type { BundleComponentRow, BundleRow, BundleVersionRow } from '../../../../../packages/api-contracts/src/index';

function versionRow(overrides: Partial<BundleVersionRow> = {}): BundleVersionRow {
  return {
    id: 'v-1',
    bundleId: 'b-1',
    bundleCode: 'BND-1',
    versionNo: 'v1',
    name: '套装 v1',
    status: 'DRAFT',
    statusLabel: '草稿',
    effectiveFrom: '2026-03-01T00:00:00Z',
    effectiveTo: null,
    publishedBy: null,
    publishedAt: null,
    note: '',
    componentCount: 2,
    totalQuantity: 2.5,
    effective: true,
    editable: true,
    version: 0,
    createdAt: '2026-02-01T00:00:00Z',
    updatedAt: '2026-02-01T00:00:00Z',
    ...overrides,
  };
}

describe('3.5.1.1 套装版本管理 · 码表与展示', () => {
  it('版本状态与 V23 的 CHECK 同一口径', () => {
    expect(VERSION_STATUSES.map((s) => s.value)).toEqual(['DRAFT', 'PUBLISHED']);
    expect(BUNDLE_STATUSES.map((s) => s.value)).toEqual(['ACTIVE', 'ARCHIVED']);
    expect(statusLabel('DRAFT')).toBe('草稿');
    expect(statusLabel('PUBLISHED')).toBe('已发布');
    // 未登记的状态原样透出，不要吞成一个假的「草稿」。
    expect(statusLabel('ARCHIVED')).toBe('ARCHIVED');
    expect(statusTone('PUBLISHED')).toBe('success');
    expect(statusTone('DRAFT')).toBe('warning');
    expect(statusHint('PUBLISHED')).toContain('不可修改');
  });

  it('生效区间按半开区间解释，且写清终点归下一版', () => {
    const text = rangeText('2026-03-01T00:00:00Z', null);
    expect(text).toContain('一直有效');
    expect(text).toContain('+∞');
    const closed = rangeText('2026-03-01T00:00:00Z', '2026-06-01T00:00:00Z');
    // 这句话是给运营看的：终点那一瞬间到底归谁，不写清楚就会有人以为终点仍算本版。
    expect(closed).toContain('终点那一瞬间归下一版');
  });
});

describe('3.5.1.1 套装版本管理 · 冻结规则要在界面上就讲清', () => {
  it('已发布版本整版不可改，唯一例外是向前关闭', () => {
    expect(canEditVersion(versionRow({ status: 'DRAFT' }))).toBe(true);
    expect(canEditVersion(versionRow({ status: 'PUBLISHED' }))).toBe(false);
    // 已发布版本上唯一还能做的写操作。
    expect(canCloseVersion(versionRow({ status: 'PUBLISHED' }))).toBe(true);
    expect(canCloseVersion(versionRow({ status: 'DRAFT' }))).toBe(false);
  });

  it('「已发布不可改」要逐条列出被冻结的字段', () => {
    // 笼统一句「不能改」会让人反复试到底哪里能改。
    expect(FROZEN_FIELDS).toContain('套装名称');
    expect(FROZEN_FIELDS).toContain('版本号');
    expect(FROZEN_FIELDS).toContain('生效起点');
    expect(FROZEN_FIELDS).toContain('组件清单');
  });

  it('「提前停用」把限制说全：只能收紧、只能往前', () => {
    const hint = closeHint(versionRow({ versionNo: 'v3', status: 'PUBLISHED' }));
    expect(hint).toContain('v3');
    expect(hint).toContain('当前时刻之后');
    expect(hint).toContain('追溯改写');
  });
});

describe('3.5.1.1 套装版本管理 · 时间与时区', () => {
  it('本地输入转带时区 ISO，不按 UTC 直接拼', () => {
    // 若按 UTC 直接拼，东八区选了 18:00 会变成 18:00Z = 次日 02:00 的生效起点。
    const instant = localInputToInstant('2026-03-01T18:00');
    expect(instant).toMatch(/Z$/);
    expect(new Date(instant).getTime()).toBe(new Date('2026-03-01T18:00').getTime());
    expect(localInputToInstant('')).toBe('');
  });

  it('ISO 回填成 datetime-local 能显示的写法', () => {
    const local = instantToLocalInput('2026-03-01T00:00:00Z');
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    // 往返一次仍然是同一时刻 —— 回填后不保存也不会平移。
    expect(new Date(localInputToInstant(local)).getTime()).toBe(
      new Date('2026-03-01T00:00:00Z').getTime(),
    );
    expect(instantToLocalInput(null)).toBe('');
    expect(instantToLocalInput('不是时间')).toBe('');
  });
});

describe('3.5.1.1 套装版本管理 · 组件数量', () => {
  it('数量必须大于 0，且小数位不超过 6', () => {
    expect(quantityProblem('2')).toBeNull();
    // 0.5 件是真实场景（按计量单位组合），不能因为有小数就拒。
    expect(quantityProblem('0.5')).toBeNull();
    expect(quantityProblem('0.000001')).toBeNull();
    expect(quantityProblem('0')).toContain('必须大于 0');
    expect(quantityProblem('-1')).toContain('必须大于 0');
    expect(quantityProblem('')).toContain('不能为空');
    expect(quantityProblem('abc')).toContain('不是数字');
    expect(quantityProblem('0.0000001')).toContain(`${QUANTITY_SCALE} 位小数`);
  });

  it('组件清单规模：至少 1 个，至多 50 个', () => {
    expect(componentCountProblem(0)).toContain('至少要有一个组件');
    expect(componentCountProblem(1)).toBeNull();
    expect(componentCountProblem(MAX_COMPONENTS_PER_VERSION)).toBeNull();
    expect(componentCountProblem(MAX_COMPONENTS_PER_VERSION + 1)).toContain(
      '订单展开出来的子行就没人核得对了',
    );
  });

  it('同一 SKU 出现两次会被拦下', () => {
    expect(
      componentsProblem([
        { skuId: 'sku-a', quantity: '1' },
        { skuId: 'sku-b', quantity: '2' },
      ]),
    ).toBeNull();
    expect(
      componentsProblem([
        { skuId: 'sku-a', quantity: '1' },
        { skuId: 'sku-a', quantity: '2' },
      ]),
    ).toContain('出现了两次');
    expect(componentsProblem([{ skuId: '', quantity: '1' }])).toContain('都要选一个 SKU');
    expect(componentsProblem([])).toContain('至少要有一个组件');
  });

  it('空组件清单要说明「发布时会被拒绝」，不能显示成「暂无数据」', () => {
    const summary = componentSummary([], null);
    expect(summary).toContain('尚未填组件');
    expect(summary).toContain('发布时会被拒绝');
    expect(componentSummary([{ quantity: 2 } as BundleComponentRow], 2)).toContain('每件合计 2');
  });
});

describe('3.5.1.1 套装版本管理 · 生效区间校验', () => {
  it('终点必须严格晚于起点（空区间等于「从不生效」）', () => {
    expect(rangeOrderProblem('2026-03-01T00:00', '')).toBeNull();
    expect(rangeOrderProblem('2026-03-01T00:00', '2026-06-01T00:00')).toBeNull();
    expect(rangeOrderProblem('2026-03-01T00:00', '2026-03-01T00:00')).toContain('等于「从不生效」');
    expect(rangeOrderProblem('', '')).toContain('生效起点不能为空');
  });

  it('提前停用：必须落在未来，且不能比原终点更晚', () => {
    // 两个时刻都用 **datetime-local 写法**（无时区，按本地解析），
    // 因为 closeToProblem 收的就是 datetime-local 的值；混用 ISO 会差一个时区偏移。
    const local = (days: number) => {
      const d = new Date(Date.now() + days * 86400_000);
      const pad = (n: number) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    };
    const future = local(30);
    const later = local(60);
    expect(closeToProblem(future, null)).toBeNull();
    // 本版 30 天后结束，收紧到 20 天后是合法的向前关闭。
    expect(closeToProblem(local(20), new Date(Date.now() + 30 * 86400_000).toISOString())).toBeNull();
    expect(closeToProblem('', null)).toContain('一直有效');
    expect(closeToProblem('2020-01-01T00:00', null)).toContain('追溯改写');
    // 比原终点更晚的 datetime-local 写法
    expect(closeToProblem(later, new Date(Date.now() + 30 * 86400_000).toISOString())).toContain(
      '只能收紧不能往后延',
    );
  });
});

describe('3.5.1.1 套装版本管理 · 字段与词表校验', () => {
  it('必填与长度上限与后端列宽一致', () => {
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('X'.repeat(65))).toContain('64');
    expect(versionNoProblem('')).toContain('不能为空');
    expect(versionNoProblem('X'.repeat(33))).toContain('32');
    expect(versionNameProblem('')).toContain('不能为空');
    expect(versionNameProblem('X'.repeat(201))).toContain('200');
    expect(noteProblem('X'.repeat(501))).toContain('500');
    expect(noteProblem('')).toBeNull();
  });

  it('空表单给的是可提交的形状而不是 undefined', () => {
    expect(emptyBundleInput()).toEqual({ code: '', note: '', version: 0 });
    // effectiveTo 为 null 表示「一直有效」，不是空字符串 —— 后端把空串当合法输入会歧义。
    expect(emptyVersionInput()).toEqual({
      versionNo: '',
      name: '',
      effectiveFrom: '',
      effectiveTo: null,
      note: '',
      version: 0,
    });
  });

  it('回填表单时不丢乐观锁版本号', () => {
    const row = versionRow({ version: 7, status: 'PUBLISHED' });
    expect(toVersionInput(row).version).toBe(7);
    // 契约里 quantity 是 number（后端 BigDecimal + numeric(19,6)），0.5 不能退化成 1。
    expect(
      toComponentInput({
        id: 'c-1',
        versionId: 'v-1',
        skuId: 'sku-a',
        skuCode: 'SKU-A',
        skuName: '甲',
        quantity: 0.5,
        role: '配件',
        note: '',
      }),
    ).toEqual({ skuId: 'sku-a', quantity: 0.5, role: '配件', note: '' });
  });
});

describe('3.5.1.1 套装版本管理 · 展示与错误', () => {
  it('「此刻没有生效版本」要说明是真状态，不是没有版本', () => {
    const noVersions: Pick<BundleRow, 'effectiveVersionNo' | 'versionCount'> = {
      effectiveVersionNo: null,
      versionCount: 0,
    };
    expect(effectiveLabel(noVersions)).toBe('还没有任何版本');
    expect(effectiveLabel({ effectiveVersionNo: null, versionCount: 2 })).toContain(
      '此刻没有生效版本',
    );
    expect(effectiveLabel({ effectiveVersionNo: 'v2', versionCount: 2 })).toBe('此刻生效：v2');
  });

  it('后端返回的是可执行的中文原因，原样透出', () => {
    expect(errorText(new Error('生效区间与已发布版本 v2 重叠'))).toContain('重叠');
    expect(errorText('奇怪的东西')).toBe('请求失败，请稍后重试');
  });
});

describe('3.5.1.1 套装版本管理 · 写操作语义', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('发布是 POST /publish，关闭是 PUT /close，组件是整体替换的 PUT', async () => {
    // 钉住接口形状：publish 用 POST（语义是「已发布」不是「已受理」），
    // close 用 PUT（改的是版本的一个字段），components 用 PUT 且**整体替换**——
    // 有人把它改成增删式时，这个用例会先红。
    const calls: { url: string; method: string; body: unknown }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string, init: RequestInit) => {
        calls.push({ url, method: init.method ?? 'GET', body: init.body ? JSON.parse(String(init.body)) : null });
        return Promise.resolve(new Response('{}', { status: 200 }));
      }),
    );

    await publishVersion('v-1');
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toContain('/versions/v-1/publish');

    await closeVersion('v-1', '2027-01-01T00:00:00Z');
    expect(calls[1]?.method).toBe('PUT');
    expect(calls[1]?.url).toContain('/versions/v-1/close');
    expect(calls[1]?.url).toContain('effectiveTo=2027-01-01');

    await replaceComponents('v-1', {
      components: [{ skuId: 'sku-a', quantity: 1, role: null, note: null }],
      version: 3,
    });
    expect(calls[2]?.method).toBe('PUT');
    expect(calls[2]?.url).toContain('/versions/v-1/components');
    // 整体替换：一份提交就是保存后的全部内容，并带上乐观锁版本。
    expect(calls[2]?.body).toEqual({
      components: [{ skuId: 'sku-a', quantity: 1, role: null, note: null }],
      version: 3,
    });
  });
});
