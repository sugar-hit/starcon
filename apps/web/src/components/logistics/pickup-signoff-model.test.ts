import { describe, expect, it } from 'vitest';
import {
  EMPTY_PICKUPSIGNOFF_FILTERS,
  buildCaseQuery,
  deadlineText,
  deliveryStateTone,
  deliveryText,
  formatHours,
  formatMoment,
  gapText,
  notificationStatusLabel,
  pickupStateTone,
  pickupText,
  reminderKindLabel,
  signoffStatusLabel,
  signoffStatusTone,
  validateAttestInput,
  validateSlaRuleInput,
  type PickupSignoffCaseRow,
} from './pickup-signoff-model';

/**
 * 10.3.2.2 揽收与签收确认前端模型测试。
 *
 * <p>本页最容易做成假活的两件事，测试逐条钉住：
 *
 * <ol>
 *   <li><b>超时不靠页面算</b>：页面只展示后端给的 `pickupState` 与 `overdueHours`，
 *       **不自己比时刻**。所以「超时」这个结论在页面上必须原样来自后端，
 *       并且超时时长用后端给的定点数，不在页面上重算。</li>
 *   <li><b>缺口不靠页面吞掉</b>：运单号对不上时，页面必须把缺口**说出来**
 *       （缺运单号 / 缺轨迹主体 / 运单号不唯一 / 未配时限规则），
 *       不能用「—」或者「无」把它抹平 —— 抹平之后界面上与「这一件很顺利」
 *       无法区分。</li>
 * </ol>
 */

function row(over: Partial<PickupSignoffCaseRow> = {}): PickupSignoffCaseRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    caseNo: 'PSO-20261015-000001',
    sourceManifestItemId: '22222222-2222-2222-2222-222222222222',
    sourceBatchId: '33333333-3333-3333-3333-333333333333',
    sourceBatchNo: 'HNB-20261015-000001',
    sourceBatchStatus: 'DISPATCH_PENDING',
    sourceSubjectId: '44444444-4444-4444-4444-444444444444',
    subjectMatchCount: 1,
    masterWaybillNo: 'WB-1',
    parcelNo: 1,
    parcelRef: 'BOX-1',
    carrierCode: 'SF',
    channelId: null,
    salesOrderNo: 'SO-1',
    destinationCountry: 'US',
    grossWeightKg: 1.5,
    handoverAt: '2026-10-15T09:30:00Z',
    dispatchedAt: '2026-10-15T09:30:00Z',
    slaRuleId: '55555555-5555-5555-5555-555555555555',
    pickupSlaHours: 24,
    reminderLeadHours: 4,
    pickupDeadlineAt: '2026-10-16T09:30:00Z',
    pickupState: 'PENDING',
    pickupStateLabel: '未确认揽收',
    pickupConfirmedAt: null,
    pickupEvidenceSource: '',
    pickupEvidenceRef: '',
    deliveryState: 'PENDING',
    deliveryStateLabel: '未确认签收',
    deliveryConfirmedAt: null,
    deliveryEvidenceSource: '',
    deliveryEvidenceRef: '',
    exceptionFlag: false,
    exceptionCode: '',
    exceptionNote: '',
    exceptionAt: null,
    overdueHours: null,
    status: 'PENDING_PICKUP',
    statusLabel: '已交运待揽收',
    gapCode: '',
    gapLabel: '',
    gapReason: '',
    lastReconciledAt: null,
    createdAt: '2026-10-15T09:30:01Z',
    updatedAt: '2026-10-15T09:30:01Z',
    ...over,
  };
}

describe('10.3.2.2 状态标签与语义色', () => {
  it('超时未揽收是危险色，待揽收是警示色，已揽收/已签收是成功色', () => {
    expect(signoffStatusTone('PICKUP_OVERDUE')).toBe('danger');
    expect(signoffStatusTone('PENDING_PICKUP')).toBe('warning');
    expect(signoffStatusTone('PICKED_UP')).toBe('success');
    expect(signoffStatusTone('DELIVERED')).toBe('success');
    expect(signoffStatusTone('VOID')).toBe('neutral');
  });

  it('揽收三档各有一档色，签收两档各有一档色', () => {
    expect(pickupStateTone('PENDING')).toBe('warning');
    expect(pickupStateTone('CONFIRMED')).toBe('success');
    expect(pickupStateTone('OVERDUE')).toBe('danger');
    expect(deliveryStateTone('CONFIRMED')).toBe('success');
    expect(deliveryStateTone('PENDING')).toBe('neutral');
  });

  it('标签不会把未知取值说成空', () => {
    expect(signoffStatusLabel('PICKUP_OVERDUE')).toBe('超时未揽收');
    expect(signoffStatusLabel('')).toBe('未知');
    expect(reminderKindLabel('PICKUP_OVERDUE')).toBe('超时未揽收');
    expect(notificationStatusLabel('READY')).toBe('到时机 · 待下游消费');
    // 「未到时机」与「到时机待消费」是**两档**，不能合并 ——
    // 合并之后「还没到点邀请评价」会被读成「邀请没发出去」。
    expect(notificationStatusLabel('PENDING')).not.toBe(notificationStatusLabel('READY'));
  });
});

describe('10.3.2.2 展示口径（不自己算超时）', () => {
  it('未配置时限规则时如实说「判不了超时」，不用默认值冒充', () => {
    const text = deadlineText(row({ pickupDeadlineAt: null, pickupSlaHours: null }));
    expect(text).toContain('未配时限规则');
    expect(text).toContain('判不了超时');
  });

  it('有时限时把「快照」两个字写出来（事后改规则不改已有单）', () => {
    const text = deadlineText(row());
    expect(text).toContain('2026-10-16 09:30:00');
    expect(text).toContain('快照');
  });

  it('超时时长直接用后端给的定点数，页面不重算', () => {
    const text = pickupText(
      row({ pickupState: 'OVERDUE', overdueHours: 6.25, status: 'PICKUP_OVERDUE' }),
    );
    expect(text).toContain('6.25');
    expect(text).toContain('已超时');
  });

  it('未确认揽收就说未确认，不含糊', () => {
    expect(pickupText(row())).toBe('未确认揽收');
    expect(deliveryText(row())).toBe('未确认签收');
  });

  it('已确认时把证据来源写出来（承运商事件 vs 人工登记）', () => {
    const carrier = pickupText(
      row({
        pickupState: 'CONFIRMED',
        pickupConfirmedAt: '2026-10-15T11:30:00Z',
        pickupEvidenceSource: 'CARRIER_EVENT',
        pickupEvidenceRef: 'EV-1',
      }),
    );
    expect(carrier).toContain('承运商事件');
    expect(carrier).toContain('EV-1');

    const manual = pickupText(
      row({
        pickupState: 'CONFIRMED',
        pickupConfirmedAt: '2026-10-15T11:30:00Z',
        pickupEvidenceSource: 'MANUAL_ATTESTATION',
        pickupEvidenceRef: 'MANUAL-1',
      }),
    );
    expect(manual).toContain('人工登记');
    expect(manual).toContain('MANUAL-1');
  });

  it('缺口要说出原因，不能用「无」抹平', () => {
    const text = gapText(
      row({
        gapCode: 'NO_WAYBILL',
        gapLabel: '缺运单号',
        gapReason: '交运件没有运单号，无法与承运商事件对齐',
      }),
    );
    expect(text).toContain('运单号');
    expect(text).not.toBe('—');
    expect(gapText(row())).toBe('');
  });

  it('时刻只做格式化，不做时区换算（跨时区团队各说各的会出事）', () => {
    expect(formatMoment('2026-10-15T09:30:00Z')).toBe('2026-10-15 09:30:00 Z');
    expect(formatMoment('2026-10-15T09:30:00+08:00')).toBe('2026-10-15 09:30:00 +08:00');
    expect(formatMoment(null)).toBe('—');
  });

  it('小时保留后端给的精度，不做单位换算', () => {
    expect(formatHours(24)).toBe('24 小时');
    expect(formatHours(6.25)).toBe('6.25 小时');
    expect(formatHours(null)).toBe('—');
  });
});

describe('10.3.2.2 查询串与表单校验', () => {
  it('只放非空筛选，页码与页长总是带上', () => {
    const query = buildCaseQuery(EMPTY_PICKUPSIGNOFF_FILTERS);
    expect(query).toContain('page=0');
    expect(query).toContain('size=20');
    expect(query).toContain('sort=HANDOVER_AT_DESC');
    expect(query).not.toContain('carrierCode=');
  });

  it('筛选与开关如实进查询串', () => {
    const query = buildCaseQuery({
      ...EMPTY_PICKUPSIGNOFF_FILTERS,
      carrierCode: 'SF',
      status: 'PICKUP_OVERDUE',
      keyword: 'WB-1',
      overdueOnly: true,
      exceptionOnly: true,
      page: 2,
    });
    expect(query).toContain('carrierCode=SF');
    expect(query).toContain('status=PICKUP_OVERDUE');
    expect(query).toContain('keyword=WB-1');
    expect(query).toContain('overdueOnly=true');
    expect(query).toContain('exceptionOnly=true');
    expect(query).toContain('page=2');
  });

  it('时限规则校验：承运商与揽收时限必填，小时必须为正', () => {
    expect(
      validateSlaRuleInput({
        carrierCode: '',
        channelId: null,
        pickupSlaHours: null,
        reminderLeadHours: 4,
        reviewInviteDelayHours: 0,
        afterSalesWindowHours: 168,
        status: 'ACTIVE',
        note: '',
      }),
    ).toEqual(expect.arrayContaining(['承运商代码不能为空', '揽收时限必须大于 0 小时']));

    expect(
      validateSlaRuleInput({
        carrierCode: 'SF',
        channelId: null,
        pickupSlaHours: 24,
        reminderLeadHours: -1,
        reviewInviteDelayHours: -1,
        afterSalesWindowHours: 0,
        status: 'WEIRD',
        note: '',
      }),
    ).toEqual(
      expect.arrayContaining([
        '提醒提前量不能为负',
        '评价邀请延迟不能为负',
        '售后窗口必须大于 0 小时',
        '规则状态只能是 ACTIVE 或 DISABLED',
      ]),
    );
  });

  it('人工确认校验：凭据号必填（没有凭据号的确认无法用于事后追责）', () => {
    expect(validateAttestInput({ kind: 'PICKUP', evidenceRef: '' })).toContain('凭据号不能为空');
    expect(validateAttestInput({ kind: 'NOPE', evidenceRef: 'X' })).toContain(
      '确认类型只能是 PICKUP（揽收）或 DELIVERY（签收）',
    );
    expect(validateAttestInput({ kind: 'PICKUP', evidenceRef: 'MANUAL-1' })).toEqual([]);
  });
});
