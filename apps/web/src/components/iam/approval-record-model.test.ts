import { describe, expect, it } from 'vitest';
import {
  RECORD_ACTIONS,
  RECORD_STATUSES,
  actionLabel,
  actionTone,
  amountLabel,
  buildExportQuery,
  buildQuery,
  durationLabel,
  durationTone,
  emptyFilters,
  isFiltered,
  isUuid,
  localToInstant,
  pageCount,
  rateLabel,
  statusLabel,
  statusTone,
  timelineHeadline,
  topKey,
  validateFilters,
  type RecordFilters,
} from './approval-record-model';

const filters = (over: Partial<RecordFilters> = {}): RecordFilters => ({
  ...emptyFilters(),
  ...over,
});

describe('码值映射', () => {
  it('结果状态与审批动作逐字覆盖后端 CHECK 里的真实取值', () => {
    // 少一个就意味着界面上有一种真实会出现的结果显示成空白或码值本身
    expect([...RECORD_STATUSES]).toEqual(['PENDING', 'APPROVED', 'REJECTED']);
    expect([...RECORD_ACTIONS]).toEqual(['APPROVE', 'REJECT', 'TRANSFER']);
    expect(statusLabel('PENDING')).toBe('待审批');
    expect(statusLabel('APPROVED')).toBe('已通过');
    expect(statusLabel('REJECTED')).toBe('已驳回');
    expect(actionLabel('APPROVE')).toBe('通过');
    expect(actionLabel('REJECT')).toBe('驳回');
    expect(actionLabel('TRANSFER')).toBe('转交');
  });

  it('撤回刻意不在词表里：V160 的 CHECK 未包含 WITHDRAWN', () => {
    expect(RECORD_STATUSES).not.toContain('WITHDRAWN');
    expect(RECORD_ACTIONS).not.toContain('WITHDRAW');
    // 未知码回落到码值本身而不是空白，但**不**伪装成已交付的状态
    expect(statusLabel('WITHDRAWN')).toBe('WITHDRAWN');
  });

  it('语气色按语义分档', () => {
    expect(statusTone('APPROVED')).toBe('success');
    expect(statusTone('REJECTED')).toBe('warning');
    expect(statusTone('PENDING')).toBe('primary');
    expect(statusTone('UNKNOWN')).toBe('neutral');
    expect(actionTone('APPROVE')).toBe('success');
    expect(actionTone('TRANSFER')).toBe('neutral');
  });
});

describe('时长渲染', () => {
  it('按秒/分/小时/天四档换算', () => {
    expect(durationLabel(0)).toBe('0 秒');
    expect(durationLabel(45)).toBe('45 秒');
    expect(durationLabel(60)).toBe('1 分钟');
    expect(durationLabel(3599)).toBe('59 分钟');
    expect(durationLabel(3600)).toBe('1 小时 0 分钟');
    expect(durationLabel(3660)).toBe('1 小时 1 分钟');
    expect(durationLabel(86_400)).toBe('1 天 0 小时');
    expect(durationLabel(90_000)).toBe('1 天 1 小时');
  });

  it('负数按 0 处理 —— 后端算出的时长不该让界面显示负数', () => {
    expect(durationLabel(-10)).toBe('0 秒');
    expect(durationTone(-10)).toBe('neutral');
  });

  it('压得越久语气越重', () => {
    expect(durationTone(60)).toBe('neutral');
    expect(durationTone(7200)).toBe('primary');
    expect(durationTone(48 * 3600)).toBe('warning');
    expect(durationTone(96 * 3600)).toBe('danger');
  });
});

describe('时间线一句话', () => {
  it('通过与驳回带操作人', () => {
    expect(timelineHeadline('APPROVE', '张三', null)).toBe('张三 通过');
    expect(timelineHeadline('REJECT', '李四', null)).toBe('李四 驳回');
  });

  it('转交是委派而不是表态，说法必须不同', () => {
    expect(timelineHeadline('TRANSFER', '张三', '王五')).toBe('张三 转交给 王五');
  });

  it('解析不出成员名时说「未知操作人」而不是留空', () => {
    // 没有 member.read 权限时拿不到用户名是正常的，留空会被误读成「没留意见的人」
    expect(timelineHeadline('APPROVE', null, null)).toBe('未知操作人 通过');
    expect(timelineHeadline('TRANSFER', null, null)).toBe('未知操作人 转交（未指定目标）');
  });
});

describe('金额与比率', () => {
  it('没有金额时明说不适用，不留空白让人猜是不是加载中', () => {
    expect(amountLabel(null, null)).toBe('不适用金额审批');
    expect(amountLabel(100, 'USD')).toBe('USD 100');
    expect(amountLabel(100, null)).toBe('100');
  });

  it('通过率转百分比，后端给的是 0..1', () => {
    expect(rateLabel(0.5)).toBe('50.0%');
    expect(rateLabel(0)).toBe('0.0%');
    expect(rateLabel(1)).toBe('100.0%');
    expect(rateLabel(Number.NaN)).toBe('—');
  });
});

describe('筛选校验', () => {
  it('空筛选永远合法', () => {
    expect(validateFilters(filters())).toEqual([]);
  });

  it('金额必须是非负数字且最多 6 位小数', () => {
    expect(validateFilters(filters({ minAmount: '-1' }))).toContain(
      '金额下限需为非负数字，最多 6 位小数',
    );
    expect(validateFilters(filters({ maxAmount: 'abc' }))).toContain(
      '金额上限需为非负数字，最多 6 位小数',
    );
    expect(validateFilters(filters({ minAmount: '1.1234567' }))).toContain(
      '金额下限需为非负数字，最多 6 位小数',
    );
    expect(validateFilters(filters({ minAmount: '100', maxAmount: '50' }))).toContain(
      '金额下限不能大于上限',
    );
  });

  it('金额上下限相等是合法的（闭区间的一端）', () => {
    expect(validateFilters(filters({ minAmount: '100', maxAmount: '100' }))).toEqual([]);
  });

  it('成员 ID 必须是 UUID', () => {
    expect(validateFilters(filters({ applicantId: 'nope' }))).toContain(
      '申请人需为成员 ID（UUID）',
    );
    expect(validateFilters(filters({ actorId: 'nope' }))).toContain('审批人需为成员 ID（UUID）');
    expect(
      validateFilters(filters({ applicantId: '0f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60' })),
    ).toEqual([]);
    expect(isUuid('0F6A1A4E-0E1A-4A0E-9F3A-1B2C3D4E5F60')).toBe(true);
  });

  it('状态与动作只接受后端 CHECK 里的真实取值', () => {
    expect(validateFilters(filters({ status: 'WITHDRAWN' }))).toContain(
      '状态需为 PENDING / APPROVED / REJECTED',
    );
    expect(validateFilters(filters({ action: 'WITHDRAW' }))).toContain(
      '动作需为 APPROVE / REJECT / TRANSFER',
    );
    expect(validateFilters(filters({ status: 'APPROVED', action: 'TRANSFER' }))).toEqual([]);
  });

  it('时间窗倒置要报错', () => {
    expect(
      validateFilters(
        filters({ submittedFrom: '2026-10-18T12:00', submittedTo: '2026-10-18T10:00' }),
      ),
    ).toContain('提交开始时间不能晚于结束时间');
    expect(
      validateFilters(filters({ decidedFrom: '2026-10-18T12:00', decidedTo: '2026-10-18T10:00' })),
    ).toContain('动作开始时间不能晚于结束时间');
    // 相等是合法的（单点时间窗）
    expect(
      validateFilters(
        filters({ submittedFrom: '2026-10-18T12:00', submittedTo: '2026-10-18T12:00' }),
      ),
    ).toEqual([]);
  });
});

describe('查询串拼装', () => {
  it('空值一律不进串', () => {
    const query = buildQuery(filters());
    expect(query).not.toContain('minAmount=');
    expect(query).not.toContain('instanceNo=');
    expect(query).not.toContain('status=');
    expect(query).toBe('?sort=NEWEST&size=20');
  });

  it('每个筛选维度都拼得进去', () => {
    const query = buildQuery(
      filters({
        instanceNo: 'AP-1',
        documentTypeCode: 'PURCHASE_ORDER',
        sourceSystem: 'trade',
        status: 'APPROVED',
        applicantId: '0f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60',
        actorId: '1f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60',
        nodeKey: 'N1',
        action: 'REJECT',
        currencyCode: 'usd',
        minAmount: '100',
        maxAmount: '900',
      }),
    );
    const params = new URLSearchParams(query);
    expect(params.get('instanceNo')).toBe('AP-1');
    expect(params.get('documentTypeCode')).toBe('PURCHASE_ORDER');
    expect(params.get('sourceSystem')).toBe('trade');
    expect(params.get('status')).toBe('APPROVED');
    expect(params.get('applicantId')).toBe('0f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60');
    expect(params.get('actorId')).toBe('1f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60');
    expect(params.get('nodeKey')).toBe('N1');
    expect(params.get('action')).toBe('REJECT');
    // 币种统一大写，否则后端会拿小写去比 char(3) 一条都查不到
    expect(params.get('currencyCode')).toBe('USD');
    expect(params.get('minAmount')).toBe('100');
    expect(params.get('maxAmount')).toBe('900');
  });

  it('提交时间与动作时间是两个独立的时间窗', () => {
    const params = new URLSearchParams(
      buildQuery(
        filters({
          submittedFrom: '2026-10-01T00:00',
          submittedTo: '2026-10-31T00:00',
          decidedFrom: '2026-10-05T00:00',
          decidedTo: '2026-10-06T00:00',
        }),
      ),
    );
    expect(params.get('submittedFrom')).toBeTruthy();
    expect(params.get('submittedTo')).toBeTruthy();
    expect(params.get('decidedFrom')).toBeTruthy();
    expect(params.get('decidedTo')).toBeTruthy();
    expect(params.get('submittedFrom')).not.toBe(params.get('decidedFrom'));
  });

  it('分页：首页不带 page，翻页才带', () => {
    expect(new URLSearchParams(buildQuery(filters({ page: 0 }))).get('page')).toBeNull();
    expect(new URLSearchParams(buildQuery(filters({ page: 3 }))).get('page')).toBe('3');
  });
});

describe('导出查询串', () => {
  it('刻意不带分页：导出的是当前筛选命中的全部行，不是当前这一页', () => {
    const params = new URLSearchParams(buildExportQuery(filters({ page: 5, size: 20 })));
    expect(params.get('page')).toBeNull();
    expect(params.get('size')).toBeNull();
  });

  it('筛选条件一个不少地保留', () => {
    const params = new URLSearchParams(
      buildExportQuery(filters({ status: 'REJECTED', sourceSystem: 'trade', page: 2 })),
    );
    expect(params.get('status')).toBe('REJECTED');
    expect(params.get('sourceSystem')).toBe('trade');
  });
});

describe('本地时间控件换算', () => {
  it('datetime-local 的墙钟值转成 ISO 瞬时值', () => {
    // 不转的话后端 Instant.parse 会 400，页面表现为「怎么填都不生效」
    const instant = localToInstant('2026-10-18T08:30');
    expect(instant).toBeTruthy();
    expect(new Date(instant as string).getTime()).toBe(Date.parse('2026-10-18T08:30'));
  });

  it('空值与不可解析值返回 null，不进查询串', () => {
    expect(localToInstant('')).toBeNull();
    expect(localToInstant('   ')).toBeNull();
    expect(localToInstant('不是时间')).toBeNull();
    expect(buildQuery(filters({ submittedFrom: '不是时间' }))).not.toContain('submittedFrom');
  });
});

describe('空态判定与分页', () => {
  it('没有任何筛选时不算「筛选后无结果」', () => {
    expect(isFiltered(filters())).toBe(false);
    expect(isFiltered(filters({ sort: 'OLDEST' }))).toBe(false);
  });

  it('任一维度有值就算有筛选', () => {
    expect(isFiltered(filters({ instanceNo: 'AP-1' }))).toBe(true);
    expect(isFiltered(filters({ actorId: 'x' }))).toBe(true);
    expect(isFiltered(filters({ decidedFrom: '2026-10-18T08:00' }))).toBe(true);
  });

  it('总页数来自后端真实 total，最少一页', () => {
    expect(pageCount(0, 20)).toBe(1);
    expect(pageCount(1, 20)).toBe(1);
    expect(pageCount(20, 20)).toBe(1);
    expect(pageCount(21, 20)).toBe(2);
    expect(pageCount(100, 20)).toBe(5);
    expect(pageCount(100, 0)).toBe(0);
  });
});

describe('统计分组高亮', () => {
  it('标出计数最大的一项', () => {
    expect(
      topKey([
        { key: 'PENDING', instanceCount: 3 },
        { key: 'APPROVED', instanceCount: 9 },
      ]),
    ).toBe('APPROVED');
  });

  it('空分组返回 null 而不是报错', () => {
    expect(topKey([])).toBeNull();
  });
});
