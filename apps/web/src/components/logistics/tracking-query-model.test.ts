import { describe, expect, it } from 'vitest';

import {
  EMPTY_TRACKING_FILTERS,
  EMPTY_TRACKING_PULL_TASK,
  buildSubjectQuery,
  carrierEtaText,
  etaStatusLabel,
  etaStatusTone,
  eventSourceLabel,
  exceptionText,
  formatMoment,
  formatWeight,
  latestText,
  nodeTypeLabel,
  parseWaybillList,
  promiseText,
  pullItemStatusLabel,
  pullTaskStatusLabel,
  pullTaskStatusTone,
  serviceLevelLabel,
  subjectStatusLabel,
  validatePullTaskInput,
} from './tracking-query-model';
import type { TrackingSubjectRow } from './tracking-query-model';

/**
 * 10.3.1.1 轨迹查询前端模型测试。
 *
 * <p>只测**纯函数**：渲染与取数由 playwright / 后端集成测试兜。
 *
 * <p>断言重点放在「错了会让人误判货况」的那几条上：
 * ① 时效状态的**语义色**（NO_PROMISE / NO_ETA 是「判不了」，不是「准点」——
 *    给它们打上绿色，界面就在替人说「没问题」）；
 * ② 承诺与预计**分列**（合并成一句话就分不清谁承诺的）；
 * ③ 异常摘要**不装懂**（没有异常码就说「未给异常码」）。
 *
 * <p>⚠️ 这里断言的前端逻辑**不是安全边界**：去重是 DB 唯一约束（V212），
 * 异常只标记不处置是服务层的边界，前端都绕得过去。页面自己再去个重 / 自己判延误，
 * 才是真正危险的地方 —— 那些断言钉的是「页面不自己算」。
 */
function subject(overrides: Partial<TrackingSubjectRow> = {}): TrackingSubjectRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    sourceShipmentRecordId: '22222222-2222-2222-2222-222222222222',
    sourceRecordNo: 'SR-2026-000001',
    salesOrderNo: 'SO-2026-0001',
    outboundOrderNo: 'OB-2026-0001',
    warehouseRef: 'WH-DE-1',
    carrierCode: 'SF',
    masterWaybillNo: 'WB-1',
    destinationCountry: 'DE',
    channelId: null,
    channelCode: null,
    channelName: null,
    grossWeightKg: 2,
    serviceLevel: 'STANDARD',
    parcelCount: 1,
    shippedAt: '2026-10-01T08:00:00Z',
    latestNode: 'IN_TRANSIT',
    latestNodeLabel: '运输中',
    latestEventCode: 'IT',
    latestDescription: '运输中',
    latestOccurredAt: '2026-10-03T11:00:00Z',
    latestReceivedAt: '2026-10-03T11:05:00Z',
    eventCount: 3,
    exceptionFlag: false,
    exceptionCount: 0,
    lastExceptionCode: '',
    lastExceptionNote: '',
    lastExceptionAt: null,
    status: 'ACTIVE',
    statusLabel: '在途',
    etaStatus: 'ON_TIME',
    etaStatusLabel: '按时',
    promisedEtaEarliestAt: '2026-10-05T08:00:00Z',
    promisedEtaAt: '2026-10-09T08:00:00Z',
    promisedTransitDaysMin: 5,
    promisedTransitDaysMax: 8,
    carrierEtaAt: '2026-10-08T08:00:00Z',
    matchedPromiseCount: 1,
    syncedAt: '2026-10-03T11:05:00Z',
    ...overrides,
  };
}

describe('10.3.1.1 节点与状态标签', () => {
  it('九档节点类型都有中文标签，未知值原样返回而不是空串', () => {
    expect(nodeTypeLabel('PICKED_UP')).toBe('已揽收');
    expect(nodeTypeLabel('IN_TRANSIT')).toBe('运输中');
    expect(nodeTypeLabel('CUSTOMS')).toBe('清关中');
    expect(nodeTypeLabel('TRANSFER')).toBe('转运中');
    expect(nodeTypeLabel('OUT_FOR_DELIVERY')).toBe('派送中');
    expect(nodeTypeLabel('DELIVERED')).toBe('已签收');
    expect(nodeTypeLabel('EXCEPTION')).toBe('异常');
    expect(nodeTypeLabel('RETURNED')).toBe('已退回');
    expect(nodeTypeLabel('NO_TRACE')).toBe('无轨迹');
    expect(nodeTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('主体状态 / 拉取任务状态 / 逐票状态都有标签', () => {
    expect(subjectStatusLabel('ACTIVE')).toBe('在途');
    expect(subjectStatusLabel('DELIVERED')).toBe('已签收');
    expect(pullTaskStatusLabel('NO_CHANNEL')).toBe('承运商轨迹通道未接通');
    expect(pullItemStatusLabel('DUPLICATE')).toBe('全部重复');
    expect(pullItemStatusLabel('NO_EVENT')).toBe('承运商回复无轨迹');
    expect(eventSourceLabel('CARRIER_PUSH')).toBe('承运商推送');
    expect(serviceLevelLabel('EXPRESS')).toBe('特快');
  });
});

describe('10.3.1.1 时效状态的语义色', () => {
  it('判不了的两种状态不能用绿色 —— 界面不能替人说「没问题」', () => {
    expect(etaStatusTone('ON_TIME')).toBe('success');
    expect(etaStatusTone('AT_RISK')).toBe('warning');
    expect(etaStatusTone('DELAYED')).toBe('danger');
    // NO_PROMISE = 没有承诺可比，NO_ETA = 承运商还没给预计 —— 都是「判不了」
    expect(etaStatusTone('NO_PROMISE')).toBe('neutral');
    expect(etaStatusTone('NO_ETA')).toBe('neutral');
  });

  it('拉取任务的「通道未接通」是能力缺口，不是业务失败', () => {
    expect(pullTaskStatusTone('SUCCEEDED')).toBe('success');
    expect(pullTaskStatusTone('PARTIAL')).toBe('warning');
    expect(pullTaskStatusTone('FAILED')).toBe('danger');
    expect(pullTaskStatusTone('NO_CHANNEL')).toBe('neutral');
  });
});

describe('10.3.1.1 承诺与预计分列', () => {
  it('承诺文本与承运商预计文本来自两个字段，不合并成一句话', () => {
    const row = subject();
    expect(promiseText(row)).toContain('5~8 天');
    expect(promiseText(row)).toContain('2026-10-05 08:00:00');
    expect(carrierEtaText(row)).toBe('2026-10-08 08:00:00 Z');
    // 承诺里**不含**承运商预计的那句时刻
    expect(promiseText(row)).not.toContain('2026-10-08');
  });

  it('没有承诺 / 没有预计时如实说没有，不用空串或 0 冒充', () => {
    const row = subject({
      promisedEtaAt: null,
      promisedEtaEarliestAt: null,
      promisedTransitDaysMin: null,
      promisedTransitDaysMax: null,
      carrierEtaAt: null,
    });
    expect(promiseText(row)).toBe('无时效承诺');
    expect(carrierEtaText(row)).toBe('承运商未给预计');
  });
});

describe('10.3.1.1 异常摘要不装懂', () => {
  it('没有异常节点时明说没有', () => {
    expect(exceptionText(subject())).toBe('无异常节点');
  });

  it('有异常但没异常码时如实说「未给异常码」，不装作说清了什么', () => {
    expect(exceptionText(subject({ exceptionFlag: true, exceptionCount: 1 }))).toBe('未给异常码');
  });

  it('有异常码与说明时两样都带出来', () => {
    expect(
      exceptionText(
        subject({
          exceptionFlag: true,
          exceptionCount: 1,
          lastExceptionCode: 'CUSTOMS_HOLD',
          lastExceptionNote: '清关滞留',
        }),
      ),
    ).toBe('CUSTOMS_HOLD：清关滞留');
  });
});

describe('10.3.1.1 时刻与重量展示', () => {
  it('时刻只格式化不换算时区', () => {
    expect(formatMoment('2026-10-03T11:00:00Z')).toBe('2026-10-03 11:00:00 Z');
    expect(formatMoment('2026-10-03T11:00:00+08:00')).toBe('2026-10-03 11:00:00 +08:00');
    expect(formatMoment(null)).toBe('—');
  });

  it('重量去掉尾随零但不做单位换算', () => {
    expect(formatWeight(2)).toBe('2 kg');
    expect(formatWeight(2.5)).toBe('2.5 kg');
    expect(formatWeight(null)).toBe('—');
  });

  it('没有轨迹时明说「尚无轨迹」，不用空串', () => {
    expect(latestText(subject({ latestOccurredAt: null }))).toBe('尚无轨迹');
  });
});

describe('10.3.1.1 清单查询串', () => {
  it('只放非空筛选，页码与页长总是带上', () => {
    const query = new URLSearchParams(
      buildSubjectQuery({ ...EMPTY_TRACKING_FILTERS, carrierCode: 'SF', page: 2, size: 20 }),
    );
    expect(query.get('carrierCode')).toBe('SF');
    expect(query.get('page')).toBe('2');
    expect(query.get('size')).toBe('20');
    expect(query.get('keyword')).toBeNull();
    expect(query.get('exceptionOnly')).toBeNull();
  });

  it('「只看异常」与目的国筛都会进查询串', () => {
    const query = new URLSearchParams(
      buildSubjectQuery({
        ...EMPTY_TRACKING_FILTERS,
        exceptionOnly: true,
        destinationCountry: 'DE',
      }),
    );
    expect(query.get('exceptionOnly')).toBe('true');
    expect(query.get('destinationCountry')).toBe('DE');
  });
});

describe('10.3.1.1 批量拉取入参', () => {
  it('运单号按行 / 逗号 / 空格分隔，去空去重', () => {
    expect(parseWaybillList('WB-1, WB-2\nWB-1  WB-3;')).toEqual(['WB-1', 'WB-2', 'WB-3']);
    expect(parseWaybillList('   ')).toEqual([]);
  });

  it('承运商必填，运单号可空（空 = 拉该承运商全部在跟踪的运单）', () => {
    expect(validatePullTaskInput(EMPTY_TRACKING_PULL_TASK)).toContain('承运商代码不能为空');
    expect(
      validatePullTaskInput({ carrierCode: 'SF', waybillNos: [], triggerSource: 'MANUAL' }),
    ).toEqual([]);
  });

  it('单轮上限 500 票', () => {
    const waybills = Array.from({ length: 501 }, (_, i) => `WB-${i}`);
    const problems = validatePullTaskInput({
      carrierCode: 'SF',
      waybillNos: waybills,
      triggerSource: 'MANUAL',
    });
    expect(problems).toContain('一次最多 500 票');
  });
});
