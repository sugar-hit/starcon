import { describe, expect, it } from 'vitest';
import {
  DECISION_LABELS,
  REASON_LABELS,
  SOURCE_LABELS,
  STATUS_LABELS,
  buildGapQuery,
  buildMapInput,
  priceCell,
  priceFilterScopeHint,
  statusTone,
  unavailableDimensions,
  validateCreateSkuForm,
  validateIgnoreForm,
  validateMapForm,
  type MapForm,
  type MappingGapCapabilities,
  type MappingGapRow,
} from './mapping-gap-model';

/**
 * 2.4.2.3 映射缺失队列 前端模型测试。
 *
 * <p>本文件钉五组事：
 *
 * <ol>
 *   <li><b>不按名称猜 SKU</b>：模型层**没有**任何「建议 SKU / 相似度」的取值函数；
 *       「映射到已有 SKU」只接受完整 UUID，不接受名字。</li>
 *   <li><b>价格位不画假数据</b>：没有快照时返回「未接入（归 05 定价）」，
 *       **不是 0、不是 "--"**；有快照时返回真实数字。</li>
 *   <li><b>筛选全在服务端</b>：空值不下发（传空串会被后端当成「筛空串」）。</li>
 *   <li><b>三个操作入口各自的必填校验</b>：SKU id / 变更原因 / 忽略原因。</li>
 *   <li><b>未交付段如实呈现</b>：capabilities 里 available=false 的维度逐条列出。</li>
 * </ol>
 */

const caps: MappingGapCapabilities = {
  dimensions: [
    {
      dimension: 'gap_list',
      description: '待映射清单',
      available: true,
      source: 'listing_mapping_gap',
      ownerModule: '',
      impact: '',
    },
    {
      dimension: 'order_source',
      description: '09 订单缺映射进此队列',
      available: false,
      source: '—',
      ownerModule: '09 订单履约（trade 服务）',
      impact: '当前 trade 侧没有推这条事件 ⇒ 订单来源未接线',
    },
    {
      dimension: 'price',
      description: '价格位（平台侧售价快照）',
      available: false,
      source: 'listing_mapping_gap.price',
      ownerModule: '05 定价与价目系统',
      impact: '平台商品来源没有价格快照',
    },
  ],
  statuses: ['PENDING', 'MAPPED', 'IGNORED'],
  decisions: ['NONE', 'MAPPED', 'CREATE_SKU', 'IGNORED'],
  gapReasons: ['VARIANT_UNMAPPED', 'MAPPING_RELEASED', 'ORDER_UNMAPPED'],
  sources: ['LISTING_SYNC', 'ORDER'],
  priceAvailable: false,
  priceNote: '平台商品来源没有价格快照（05 定价未交付）',
  priceOwnerModule: '05 定价与价目系统',
  orderSourceWired: false,
  gapNote: '🔴 09 订单来源未接线；价格归 05 定价；新建 SKU 只登记意图。',
  dependencyNote: '上游：4.1.1.1 listing / listing_variant（channel 同库同服务）。',
};

const row: MappingGapRow = {
  id: '11111111-1111-1111-1111-111111111111',
  listingId: '22222222-2222-2222-2222-222222222222',
  listingVariantId: '33333333-3333-3333-3333-333333333333',
  source: 'LISTING_SYNC',
  gapReason: 'VARIANT_UNMAPPED',
  status: 'PENDING',
  decision: 'NONE',
  shopCode: 'SHOP-1',
  shopName: '测试店铺',
  platformCode: 'AMAZON',
  externalItemId: 'ASIN-1',
  titleSnapshot: '无线蓝牙耳机 降噪版',
  externalVariantId: 'V-1',
  attributesSnapshot: '{}',
  price: null,
  currency: null,
  internalSkuId: null,
  mappingId: null,
  mappingVersionId: null,
  mappedEffectiveFrom: null,
  decidedBy: null,
  decidedAt: null,
  decisionNote: '',
  ignoreReason: '',
  backfilledAt: null,
  orderRef: '',
  waitingDays: 3,
  createdAt: '2026-10-05T10:00:00+08:00',
  updatedAt: '2026-10-05T10:00:00+08:00',
};

describe('2.4.2.3 队列行与状态口径', () => {
  it('三个状态、三个来源、四种决策都有中文标签，不出现裸枚举', () => {
    expect(STATUS_LABELS.PENDING).toBe('待映射');
    expect(STATUS_LABELS.MAPPED).toBe('已建立映射');
    expect(STATUS_LABELS.IGNORED).toBe('已忽略');
    expect(SOURCE_LABELS.LISTING_SYNC).toBe('平台商品同步');
    expect(SOURCE_LABELS.ORDER).toBe('订单缺映射');
    expect(DECISION_LABELS.CREATE_SKU).toBe('登记新建 SKU');
    expect(REASON_LABELS.MAPPING_RELEASED).toBe('映射曾建立后被解除');
  });

  it('PENDING 是「等人处理」而不是事故，用提醒色不用危险色', () => {
    expect(statusTone('PENDING')).toBe('warning');
    expect(statusTone('MAPPED')).toBe('success');
    expect(statusTone('IGNORED')).toBe('neutral');
    expect(statusTone('UNKNOWN')).toBe('neutral');
  });
});

describe('2.4.2.3 🔴 不按名称猜 SKU', () => {
  it('「映射到已有 SKU」只接受完整 UUID，不接受名字', () => {
    const base: MapForm = {
      internalSkuId: '',
      relationType: 'ONE_TO_ONE',
      componentQuantity: '',
      changeReason: '运营确认',
    };
    expect(validateMapForm(base)).toContain('内部 SKU id');
    // 名字不是 id：这里必须被拒
    expect(validateMapForm({ ...base, internalSkuId: '无线蓝牙耳机' })).toContain('UUID');
    expect(validateMapForm({ ...base, internalSkuId: 'SKU-001' })).toContain('UUID');
    expect(
      validateMapForm({ ...base, internalSkuId: '11111111-1111-1111-1111-111111111111' }),
    ).toBeNull();
  });

  it('变更原因必填（没有原因的版本历史无法回溯）', () => {
    expect(
      validateMapForm({
        internalSkuId: '11111111-1111-1111-1111-111111111111',
        relationType: 'ONE_TO_ONE',
        componentQuantity: '',
        changeReason: '   ',
      }),
    ).toContain('变更原因必填');
  });

  it('套装必须带大于 0 的组件数量；非套装不许带', () => {
    const sku = '11111111-1111-1111-1111-111111111111';
    expect(
      validateMapForm({
        internalSkuId: sku,
        relationType: 'ONE_TO_MANY',
        componentQuantity: '',
        changeReason: '套装',
      }),
    ).toContain('组件数量');
    expect(
      validateMapForm({
        internalSkuId: sku,
        relationType: 'ONE_TO_MANY',
        componentQuantity: '0',
        changeReason: '套装',
      }),
    ).toContain('大于 0');
    expect(
      validateMapForm({
        internalSkuId: sku,
        relationType: 'ONE_TO_ONE',
        componentQuantity: '2',
        changeReason: '一对一',
      }),
    ).toContain('只对一对多');
  });

  it('组装入参时非套装不带组件数量', () => {
    const input = buildMapInput({
      internalSkuId: ' 11111111-1111-1111-1111-111111111111 ',
      relationType: 'ONE_TO_ONE',
      componentQuantity: '',
      changeReason: ' 运营确认 ',
    });
    expect(input.internalSkuId).toBe('11111111-1111-1111-1111-111111111111');
    expect(input.changeReason).toBe('运营确认');
    expect(input.componentQuantity).toBeNull();
  });
});

describe('2.4.2.3 🔴 价格位不画假数据', () => {
  it('没有价格快照时显示「未接入（归 05 定价）」，不是 0、不是 "--"', () => {
    const cell = priceCell(row, caps);
    expect(cell.available).toBe(false);
    expect(cell.value).toBeNull();
    expect(cell.note).toBe(caps.priceNote);
    expect(cell.ownerModule).toBe('05 定价与价目系统');
  });

  it('有平台价快照时显示真实数字与币种', () => {
    const cell = priceCell({ price: 19.99, currency: 'USD' }, caps);
    expect(cell.available).toBe(true);
    expect(cell.value).toBe(19.99);
    expect(cell.currency).toBe('USD');
    expect(cell.note).toBeNull();
  });

  it('价格区间的适用范围写明「只覆盖带快照的行」', () => {
    expect(priceFilterScopeHint(caps, 0)).toContain('只覆盖');
    expect(priceFilterScopeHint(caps, 3)).toContain('3 条有快照');
  });
});

describe('2.4.2.3 筛选与排序全在服务端', () => {
  it('空值不下发（传空串会被后端当成「筛空串」而不是「不筛」）', () => {
    expect(buildGapQuery({})).toBe('');
    expect(buildGapQuery({ shopCode: '', search: undefined, status: '' })).toBe('');
    expect(buildGapQuery({ shopCode: 'S-1' })).toBe('?shopCode=S-1');
  });

  it('多个维度一起下发', () => {
    const query = buildGapQuery({ shopCode: 'S-1', status: 'PENDING', minPrice: '10.00' });
    expect(query).toContain('shopCode=S-1');
    expect(query).toContain('status=PENDING');
    expect(query).toContain('minPrice=10.00');
  });
});

describe('2.4.2.3 另外两个操作入口的校验', () => {
  it('忽略原因必填', () => {
    expect(validateIgnoreForm({ reason: '  ', note: '' })).toContain('忽略原因必填');
    expect(validateIgnoreForm({ reason: '平台下架的配件', note: '' })).toBeNull();
  });

  it('新建 SKU 至少要给出编码或名称', () => {
    expect(
      validateCreateSkuForm({
        requestedCode: '',
        requestedName: '',
        requestedBarcode: '',
        requestedBaseUnit: '',
        note: '',
      }),
    ).toContain('至少给出');
    expect(
      validateCreateSkuForm({
        requestedCode: 'SKU-NEW-1',
        requestedName: '',
        requestedBarcode: '',
        requestedBaseUnit: '',
        note: '',
      }),
    ).toBeNull();
  });
});

describe('2.4.2.3 未交付段如实呈现', () => {
  it('available=false 的维度逐条列出，带归属模块与影响', () => {
    const dims = unavailableDimensions(caps);
    expect(dims).toHaveLength(2);
    expect(dims.map((d) => d.dimension)).toEqual(['order_source', 'price']);
    expect(dims[0]?.ownerModule).toContain('09 订单');
    expect(dims[1]?.ownerModule).toContain('05 定价');
  });

  it('capabilities 缺失时返回空数组而不是抛错', () => {
    expect(unavailableDimensions(null)).toEqual([]);
  });
});
