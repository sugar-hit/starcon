import { describe, expect, it } from 'vitest';
import type {
  ExchangeCapabilities,
  ExchangeDetail,
  ExchangeLineView,
  ExchangeRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  attentionSummary,
  canCancel,
  cancelBlocking,
  decisionBadge,
  decisionTone,
  downstreamNotices,
  emptyHint,
  exchangeListQuery,
  formatQty,
  formatTime,
  hardRules,
  reservationBadge,
  reservationTone,
  statusBadge,
  statusTone,
  unavailable,
  waitingReturnBadge,
  waitingReturnTone,
} from './exchange-authorization-model';

/**
 * 11.3.2.1 换货授权前端模型的钉桩测试。
 *
 * <p>重点钉三件容易在界面上说错话的事：
 * <ol>
 *   <li><b>「未预占」必须被渲染成危险</b>，而不是空白或「进行中」——
 *       空白会被读成「还没显示」，而那一行正是只发不收的入口；</li>
 *   <li><b>「等旧货回寄」要单独可见</b>，不能被折叠进一个总数里；</li>
 *   <li><b>可撤销与否只认后端下发的 allowedActions</b>，前端不自己推断。</li>
 * </ol>
 */

function row(overrides: Partial<ExchangeRow> = {}): ExchangeRow {
  return {
    id: 'r1',
    authorizationNo: 'EX20260401000001',
    caseId: 'c1',
    caseNo: 'AS-1',
    orderId: 'o1',
    orderNo: 'SO-1',
    status: 'ACTIVE',
    statusLabel: '生效中',
    decision: 'APPROVED',
    decisionLabel: '整量批准',
    requestedQuantity: '2.000000',
    authorizedQuantity: '2.000000',
    replacementSkuCount: 1,
    lineCount: 1,
    activeReservationCount: 1,
    releasedReservationCount: 0,
    pendingOutboundDemandCount: 1,
    waitingReturnCount: 1,
    reasonNote: '',
    authorizedBy: 'u1',
    authorizedAt: '2026-04-01T08:00:00Z',
    cancelledBy: '',
    cancelledAt: '',
    cancelReason: '',
    version: 1,
    createdAt: '2026-04-01T08:00:00Z',
    ...overrides,
  };
}

function line(overrides: Partial<ExchangeLineView> = {}): ExchangeLineView {
  return {
    id: 'l1',
    caseLineId: 'cl1',
    originalSkuId: 's1',
    originalSkuCode: 'ORIG-1',
    originalSkuName: '原商品',
    originalQuantity: '2.000000',
    replacementSkuId: 's2',
    replacementSkuCode: 'NEW-1',
    replacementSkuName: '替换商品',
    replacementReason: '换码',
    warehouseId: 'w1',
    warehouseCode: '',
    legalEntityId: 'le1',
    requestedQuantity: '2.000000',
    authorizedQuantity: '2.000000',
    reservedQuantity: '2.000000',
    reservationId: 'rs1',
    reservationNo: 'RSV-20260401-000001',
    reservationStatus: 'HELD',
    outboundDemandId: 'd1',
    outboundDemandNo: 'EXD-1',
    outboundDemandStatus: 'PENDING',
    returnRequirementId: 'x1',
    returnRequirementStatus: 'WAITING',
    waitingReturnQuantity: '2.000000',
    ...overrides,
  };
}

function detail(overrides: Partial<ExchangeDetail> = {}): ExchangeDetail {
  return {
    authorization: row(),
    lines: [line()],
    allowedActions: ['CANCEL'],
    ...overrides,
  };
}

const caps = (overrides: Partial<ExchangeCapabilities> = {}): ExchangeCapabilities => ({
  caseDelivered: true,
  reservationApiDelivered: true,
  skuCatalogDelivered: true,
  sellableApiDelivered: true,
  outboundHandoffDelivered: false,
  outboundHandoffOwnerModule: '9.3.3.1',
  oldGoodsReturnDelivered: false,
  oldGoodsReturnOwnerModule: '11.2.2.2',
  statuses: ['ACTIVE', 'CANCELLED'],
  statusLabels: ['生效中', '已撤销'],
  decisions: ['APPROVED', 'PARTIALLY_APPROVED'],
  decisionLabels: ['整量批准', '部分批准（库存不足）'],
  allowedNextActions: ['CANCEL'],
  note: '',
  hardRules: ['换货必须同时预占替换货并等待旧货回寄，缺一不可 —— 否则会出现只发不收的损失'],
  ...overrides,
});

describe('11.3.2.1 换货授权前端模型', () => {
  describe('预占状态：只发不收的危险必须看得见', () => {
    it('HELD 是成功语气且带出真实预占单号', () => {
      expect(reservationTone('HELD')).toBe('success');
      expect(reservationBadge(line())).toContain('RSV-20260401-000001');
    });

    it('RELEASED 是中性语气', () => {
      expect(reservationTone('RELEASED')).toBe('neutral');
    });

    it('没有预占记录是危险语气，不是空白也不是「进行中」', () => {
      // 这一格是本模型最重要的一条：授权了却没圈住货，就是只发不收的入口。
      // 契约里这些列是必填 string，所以「没有」的实际形态是空串而不是 undefined ——
      // 两种形态都要归到危险，否则空串会被 default 分支当成正常值渲染出去。
      expect(reservationTone('')).toBe('danger');
      expect(reservationTone(undefined)).toBe('danger');
      expect(reservationBadge(line({ reservationStatus: '', reservationNo: '' }))).toContain('危险');
    });
  });

  describe('旧货回寄：单独可见', () => {
    it('WAITING 是警告语气并显示数量', () => {
      expect(waitingReturnTone('WAITING')).toBe('warning');
      expect(waitingReturnBadge(line())).toContain('等旧货回寄');
      expect(waitingReturnBadge(line())).toContain('2');
    });

    it('WAIVED（撤销换货后免回）是中性语气', () => {
      expect(waitingReturnTone('WAIVED')).toBe('neutral');
      expect(waitingReturnBadge(line({ returnRequirementStatus: 'WAIVED' }))).toBe('已免回');
    });

    it('未登记回寄要求显示成「未登记」而不是空白', () => {
      expect(waitingReturnBadge(line({ returnRequirementStatus: '' }))).toBe('未登记');
      expect(waitingReturnBadge(line())).not.toBe('');
    });
  });

  describe('授权结论：部分批准是要人处理的状态', () => {
    it('部分批准用警告语气，不显示成成功', () => {
      expect(decisionTone('PARTIALLY_APPROVED')).toBe('warning');
      expect(decisionTone('APPROVED')).toBe('success');
    });

    it('后端给了标签就用后端的，前端不另写一份词表', () => {
      expect(decisionBadge(row({ decisionLabel: '部分批准（库存不足）' }))).toBe('部分批准（库存不足）');
      // 后端没给标签时才回落到前端词表。
      expect(decisionBadge(row({ decisionLabel: '', decision: 'PARTIALLY_APPROVED' }))).toBe(
        '部分批准（库存不足）',
      );
    });
  });

  describe('可撤销性只认后端', () => {
    it('allowedActions 含 CANCEL 才可撤销', () => {
      expect(canCancel(detail())).toBe(true);
      expect(canCancel(detail({ allowedActions: [] }))).toBe(false);
    });

    it('未打开详情时不可撤销', () => {
      expect(canCancel(null)).toBe(false);
    });

    it('按钮灰掉必须给理由，不能让理由凭空消失', () => {
      expect(cancelBlocking(detail())).toBeNull();
      expect(cancelBlocking(detail({ allowedActions: [] }))).toContain('不允许撤销');
      expect(
        cancelBlocking(
          detail({
            allowedActions: [],
            authorization: row({ status: 'CANCELLED', statusLabel: '已撤销' }),
          }),
        ),
      ).toContain('已撤销');
      expect(cancelBlocking(null)).toContain('先打开一张换货授权单');
    });
  });

  describe('硬规则与未交付下游如实显示', () => {
    it('硬规则逐条来自后端，前端不自己写一份', () => {
      expect(hardRules(caps())).toHaveLength(1);
      expect(hardRules(caps())[0]).toContain('只发不收');
      expect(hardRules(null)).toEqual([]);
    });

    it('未交付的下游写出归属模块，不留空白', () => {
      const notices = downstreamNotices(caps());
      expect(notices).toHaveLength(2);
      expect(notices.join()).toContain('9.3.3.1');
      expect(notices.join()).toContain('11.2.2.2');
    });

    it('下游已交付时不再显示提醒', () => {
      expect(
        downstreamNotices(
          caps({ outboundHandoffDelivered: true, oldGoodsReturnDelivered: true }),
        ),
      ).toEqual([]);
    });

    it('capabilities 没到位时提醒为空而不是报错', () => {
      expect(downstreamNotices(null)).toEqual([]);
    });
  });

  describe('展示工具：数量全程不当浮点用', () => {
    it('去掉无意义的尾零但保留有效小数', () => {
      expect(formatQty('2.000000')).toBe('2');
      expect(formatQty('2.500000')).toBe('2.5');
      expect(formatQty('0.000000')).toBe('0');
    });

    it('缺失值显示成 —，不用空白也不用 0', () => {
      expect(unavailable('')).toBe('—');
      expect(unavailable(undefined)).toBe('—');
      expect(unavailable('SO-1')).toBe('SO-1');
    });

    it('时间缺失或不可解析时如实显示', () => {
      expect(formatTime('')).toBe('—');
      expect(formatTime('不是时间')).toBe('不是时间');
      expect(formatTime('2026-04-01T08:00:00Z')).not.toBe('—');
    });
  });

  describe('汇总里最该被看见的两个数', () => {
    it('等回寄量与待发货量各自读出', () => {
      const s = attentionSummary({
        totalCount: 3,
        activeCount: 2,
        cancelledCount: 1,
        approvedCount: 2,
        partiallyApprovedCount: 0,
        totalRequestedQuantity: '5.000000',
        totalAuthorizedQuantity: '4.000000',
        totalPendingOutboundQuantity: '4.000000',
        totalWaitingReturnQuantity: '4.000000',
      });
      expect(s.waitingReturn).toBe(4);
      expect(s.pendingOutbound).toBe(4);
    });

    it('汇总缺失时给 0 而不是 NaN', () => {
      expect(attentionSummary(null)).toEqual({ waitingReturn: 0, pendingOutbound: 0 });
    });
  });

  describe('查询串与空态', () => {
    it('空筛选不产生任何查询参数', () => {
      expect(exchangeListQuery({})).toBe('');
      expect(exchangeListQuery({ status: '  ' })).toBe('');
    });

    it('筛选值被编码，空格被裁掉', () => {
      expect(exchangeListQuery({ keyword: '  EX 1 ' })).toBe('?keyword=EX%201');
    });

    it('分页参数永远带上', () => {
      expect(exchangeListQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
    });

    it('空态说清「怎么才会有」', () => {
      expect(emptyHint(caps())).toContain('换货');
      expect(emptyHint(caps({ caseDelivered: false }))).toContain('尚未接入');
    });
  });

  describe('状态徽标', () => {
    it('生效中是品牌语气、已撤销是中性', () => {
      expect(statusTone('ACTIVE')).toBe('brand');
      expect(statusTone('CANCELLED')).toBe('neutral');
      expect(statusBadge(row())).toBe('生效中');
    });
  });
});
