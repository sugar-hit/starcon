import { describe, expect, it } from 'vitest';
import type {
  SkuListCapabilities,
  SkuPage,
  SkuProjectionCell,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from './api';
import {
  emptySkuFilters,
  hasActiveFilters,
  isFieldAvailable,
  isProjectionRejection,
  projectionText,
  specText,
  skuExportUrl,
  skuQuery,
  type SkuFilters,
} from './sku-list-model';

function filters(patch: Partial<SkuFilters> = {}): SkuFilters {
  return { ...emptySkuFilters, ...patch };
}

const unwired: SkuListCapabilities = {
  fields: [
    {
      code: 'ON_HAND',
      label: '在仓数量',
      source: '07 库存',
      kind: 'NUMERIC',
      available: false,
      rangeFilterable: true,
      note: '上游投影未接入：库存数量归 07 库存所有（阶段 D3），catalog 无权威数据',
    },
    {
      code: 'LISTED_PLATFORMS',
      label: '在售平台',
      source: '04 刊登',
      kind: 'TEXT',
      available: false,
      rangeFilterable: false,
      note: '上游投影未接入：在售平台归 04 刊登所有，catalog 无权威数据',
    },
  ],
  availableFilters: ['spuId', 'category', 'statuses', 'keyword', 'barcode', 'spec'],
};

function cell(patch: Partial<SkuProjectionCell>): SkuProjectionCell {
  return {
    field: 'ON_HAND',
    label: '在仓数量',
    source: '07 库存',
    value: null,
    text: null,
    available: false,
    note: '上游投影未接入',
    ...patch,
  };
}

describe('skuQuery', () => {
  it('只带非空条件，空值不产生"看着像筛选"的参数', () => {
    const query = skuQuery(filters(), 0, 50);
    expect(query).toBe('page=0&size=50&sort=updated_at&order=desc');
    expect(query).not.toContain('category=');
    expect(query).not.toContain('barcode=');
  });

  it('逐个状态展开成重复的 status 参数', () => {
    const query = skuQuery(filters({ statuses: ['ACTIVE', 'DRAFT'] }), 2, 50);
    expect(query).toContain('status=ACTIVE');
    expect(query).toContain('status=DRAFT');
    expect(query).toContain('page=2');
  });

  it('对关键字做 URL 编码', () => {
    expect(skuQuery(filters({ keyword: '黑色 & 白' }), 0, 50)).toContain(
      'q=%E9%BB%91%E8%89%B2%20%26%20%E7%99%BD',
    );
  });

  it('导出链接与列表共用同一套条件', () => {
    const current = filters({ category: '3C 配件', statuses: ['ACTIVE'] });
    const exportLink = skuExportUrl(current);
    expect(exportLink).toContain('/api/catalog/catalog/skus/export?');
    for (const part of skuQuery(current, 0, 50).split('&')) {
      if (part.startsWith('page=') || part.startsWith('size=')) continue;
      expect(exportLink).toContain(part);
    }
  });
});

describe('projectionText', () => {
  it('未接入时显示"未接入"，绝不显示 0', () => {
    expect(projectionText(cell({ available: false, value: null }))).toBe('未接入');
    // 即使后端带回了 value，未接入也不能当真实数据显示。
    expect(projectionText(cell({ available: false, value: 0 }))).toBe('未接入');
  });

  it('已接入时显示真实值', () => {
    expect(projectionText(cell({ available: true, value: 12.5 }))).toBe('12.5');
    expect(projectionText(cell({ available: true, value: null, text: 'amazon' }))).toBe('amazon');
    expect(projectionText(cell({ available: true, value: null, text: null }))).toBe('—');
  });
});

describe('specText', () => {
  it('把规格轴拼成可读文本', () => {
    expect(
      specText(
        [
          { axis: '颜色', value: '黑色' },
          { axis: '容量', value: '128G' },
        ],
        'opaque',
      ),
    ).toBe('颜色=黑色 / 容量=128G');
  });

  it('没有规格轴时回落到签名原文', () => {
    expect(specText([], 'abc:3:xyz')).toBe('abc:3:xyz');
    expect(specText([], '')).toBe('—');
  });
});

describe('capabilities', () => {
  it('未接入的字段一律不可用，并带归属服务说明', () => {
    expect(isFieldAvailable(unwired, 'ON_HAND')).toBe(false);
    expect(isFieldAvailable(unwired, 'LISTED_PLATFORMS')).toBe(false);
    expect(unwired.fields.every((f) => f.note.includes('上游投影未接入'))).toBe(true);
  });

  it('能力声明还没到位时按未接入处理，不假装可用', () => {
    expect(isFieldAvailable(null, 'ON_HAND')).toBe(false);
  });

  it('已接入的字段返回可用', () => {
    const wired: SkuListCapabilities = {
      ...unwired,
      fields: [
        { ...unwired.fields[0]!, code: 'ON_HAND', available: true, note: '已接入 07 库存 投影' },
      ],
    };
    expect(isFieldAvailable(wired, 'ON_HAND')).toBe(true);
    expect(isFieldAvailable(wired, 'LISTED_PLATFORMS')).toBe(false);
  });
});

describe('hasActiveFilters', () => {
  it('默认条件不算有筛选', () => {
    expect(hasActiveFilters(emptySkuFilters)).toBe(false);
  });

  it('任一条件有值即为有筛选', () => {
    expect(hasActiveFilters(filters({ keyword: 'x' }))).toBe(true);
    expect(hasActiveFilters(filters({ statuses: ['ACTIVE'] }))).toBe(true);
    expect(hasActiveFilters(filters({ outOfStock: 'true' }))).toBe(true);
    expect(hasActiveFilters(filters({ sort: 'code' }))).toBe(false);
  });
});

describe('isProjectionRejection', () => {
  it('422 视为"依赖未接入投影"，与普通错误区分开', () => {
    expect(isProjectionRejection(new ApiError(422, '库存归 07 所有'))).toBe(true);
    expect(isProjectionRejection(new ApiError(500, '炸了'))).toBe(false);
    expect(isProjectionRejection(new Error('网络断了'))).toBe(false);
  });
});

describe('SkuPage 契约', () => {
  it('分页结果带总数，界面据此算页数', () => {
    const page: SkuPage = {
      items: [],
      total: 120,
      page: 1,
      size: 50,
    };
    expect(Math.ceil(page.total / page.size)).toBe(3);
  });
});
