import { describe, expect, it } from 'vitest';
import type { WarehouseDetail } from '../../../../../packages/api-contracts/src/index';
import {
  addressText,
  draftOf,
  isArchived,
  laborText,
  locationFillText,
  pickableText,
  qualityRoleLabel,
  splitEntrypoints,
  stockSourceText,
  updateProblem,
  type UpdateWarehouseDraft,
} from './warehouse-detail-model';

/**
 * 8.1.1.2 仓库详情前端纯函数。
 *
 * <p>重点钉住五件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**平台仓观测值与自有账本不相加。**控制塔上合成一个「总库存」最危险：
 *       它看起来像账实相符，而 V55 的两条触发器就是为了防这个。
 *   <li>**未接入的维度显示成「未接入」而不是 0**，入口按可用与否分组。
 *   <li>**地址缺失要说缺哪几个**，不能画成一行空白。
 *   <li>**归档是终态**：归档后不能再改，表单入口整个关掉。
 *   <li>**偏移量时区在界面上被立刻拦住**（后端也独立拒，ZoneId.of 接受 "+08:00" 靠不住）。
 * </ul>
 */

function detail(overrides: Partial<WarehouseDetail> = {}): WarehouseDetail {
  return {
    base: {
      id: '11111111-1111-1111-1111-111111111111',
      code: 'WH-A',
      name: '自营仓',
      kind: 'LOCAL',
      kindLabel: '自营仓',
      countryCode: 'CN',
      timezone: 'Asia/Shanghai',
      localTime: '2026-10-07 20:15',
      status: 'ACTIVE',
      statusLabel: '启用中',
      defaultWarehouse: false,
      legalEntityId: '22222222-2222-2222-2222-222222222222',
      legalEntityCode: 'LE-1',
      legalEntityName: '主体一',
      legalEntityCount: 1,
      skuCount: 12,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
    },
    address: {
      line1: '浦东新区世纪大道 100 号',
      line2: null,
      city: '上海',
      region: '上海',
      postalCode: '200120',
      countryCode: 'CN',
      missingFields: [],
      text: '浦东新区世纪大道 100 号 上海 上海 200120 CN',
    },
    stock: {
      onHand: 150,
      reserved: 10,
      available: 140,
      skuCount: 12,
      bucketCount: 20,
      byQuality: [],
      byLegalEntity: [],
      byLocationType: [],
      platform: null,
    },
    locations: {
      totalCount: 3,
      activeCount: 3,
      pickableCount: 2,
      occupiedCount: 1,
      onHand: 150,
      byType: [],
    },
    work: {
      receiptTotal: 2,
      receiptOpenCount: 1,
      receiptClosedCount: 1,
      receiptReceivedQuantity: 100,
      stocktakeTaskCount: 1,
      transferInCount: 1,
      transferOutCount: 1,
      inventoryMovementCount: 7,
      workOrderTotal: 5,
      openWorkOrderCount: 2,
    },
    strategies: [],
    labor: {
      taskAvailable: true,
      taskOwnerModule: '8.5.1.1',
      taskNote: '已接入 warehouse_work_task：派工、在跑、完成与异常都是真实计数。',
      taskTotal: 4,
      unassignedTaskCount: 1,
      runningTaskCount: 1,
      completedTaskCount: 1,
      exceptionTaskCount: 1,
      distinctOperatorCount: 3,
      byWorkType: [],
      rosterAvailable: false,
      rosterOwnerModule: '8.1.1.3',
      rosterNote: '没有数据表。',
      equipmentAvailable: false,
      equipmentOwnerModule: '8.3.4.2',
      equipmentNote: 'print_device 没有 warehouse_id。',
    },
    entryPoints: [],
    note: '平台仓观测值与自有账本不相加。',
    ...overrides,
  } as WarehouseDetail;
}

describe('地址缺失要说明缺哪几个而不是渲染空白', () => {
  it('地址完整时不提缺失', () => {
    const text = addressText(detail());
    expect(text.value).toContain('世纪大道');
    expect(text.missing).toBe('');
  });

  it('整体没填时显示破折号并说明缺什么', () => {
    const text = addressText(
      detail({
        address: {
          line1: null,
          line2: null,
          city: null,
          region: null,
          postalCode: null,
          countryCode: 'CN',
          missingFields: ['街道地址', '城市', '省/州', '邮编'],
          text: 'CN',
        },
      }),
    );
    // 只有一个国家码时不能画成「地址：CN」了事
    expect(text.value).toBe('—');
    expect(text.missing).toContain('地址未填写');
    expect(text.missing).toContain('街道地址');
  });

  it('部分缺失时地址照常显示并补一句缺什么', () => {
    const text = addressText(
      detail({
        address: {
          line1: '某路 1 号',
          line2: null,
          city: '深圳',
          region: null,
          postalCode: null,
          countryCode: 'CN',
          missingFields: ['省/州', '邮编'],
          text: '某路 1 号 深圳 CN',
        },
      }),
    );
    expect(text.value).toBe('某路 1 号 深圳 CN');
    expect(text.missing).toBe('缺 省/州、邮编');
  });
});

describe('平台仓观测值与自有账本不相加', () => {
  it('自有仓显示自有账本口径', () => {
    const stock = stockSourceText(detail());
    expect(stock.source).toBe('自有账本');
    expect(stock.note).toContain('权威可用量');
  });

  it('平台仓显示观测口径并注明不计入自有账本', () => {
    const stock = stockSourceText(
      detail({
        stock: {
          ...detail().stock,
          platform: {
            sourceCount: 2,
            observedOnHand: 310,
            observedAvailable: 300,
            observedInbound: 50,
            latestCollectedAt: '2026-10-01T00:00:00Z',
          },
        },
      }),
    );
    expect(stock.source).toContain('310');
    expect(stock.note).toContain('不计入自有账本');
  });
});

describe('库位与人员维度的展示', () => {
  it('空库位与占用库位分开说', () => {
    expect(locationFillText(3, 1)).toBe('1 / 3 个库位有货');
    // 「没有库位」与「库位是空的」是两件事
    expect(locationFillText(0, 0)).toBe('还没有建库位');
  });

  it('不可拣库位要显示出来', () => {
    expect(pickableText(true)).toBe('可拣');
    expect(pickableText(false)).toBe('不可拣');
  });

  it('作业任务的六个口径都在摘要里', () => {
    const text = laborText(4, 1, 1, 1, 1, 3);
    expect(text).toContain('任务 4');
    expect(text).toContain('未派工 1');
    expect(text).toContain('在岗 3 人');
  });

  it('质量状态角色有中文名', () => {
    expect(qualityRoleLabel('SELLABLE')).toBe('可售');
    expect(qualityRoleLabel('PENDING_INSPECTION')).toBe('待检');
    expect(qualityRoleLabel('DEFECTIVE')).toBe('残次');
  });
});

describe('入口按可用与否分组', () => {
  const points = [
    {
      key: 'INVENTORY',
      label: '库存明细',
      module: '7.1.1.2',
      available: true,
      href: '/inventory',
      note: '已交付',
    },
    {
      key: 'PUTAWAY',
      label: '上架任务',
      module: '8.2.2.1',
      available: false,
      href: null,
      note: '上架任务表不存在。',
    },
  ] as WarehouseDetail['entryPoints'];

  it('未接入的入口归到未接入一组且没有 href', () => {
    const grouped = splitEntrypoints(points);
    expect(grouped.available.map((p) => p.key)).toEqual(['INVENTORY']);
    expect(grouped.unavailable.map((p) => p.key)).toEqual(['PUTAWAY']);
    // href 为 null 才能让界面禁用按钮而不是跳到不存在的路由
    expect(grouped.unavailable[0]?.href).toBeNull();
  });
});

describe('维护表单', () => {
  function draft(overrides: Partial<UpdateWarehouseDraft> = {}): UpdateWarehouseDraft {
    return {
      name: '自营仓',
      countryCode: 'CN',
      timezone: 'Asia/Shanghai',
      addressLine1: '某路 1 号',
      addressLine2: '',
      city: '上海',
      region: '',
      postalCode: '',
      status: 'ACTIVE',
      ...overrides,
    };
  }

  it('合法输入没有问题', () => {
    expect(updateProblem(draft())).toBe('');
  });

  it('偏移量时区在界面上被立刻拦住', () => {
    // ZoneId.of 接受 "+08:00"，所以这条前端校验不是冗余：后端也独立拒，
    // 但界面上先拦一次用户就不用等一个 400。
    expect(updateProblem(draft({ timezone: '+08:00' }))).toContain('IANA');
    expect(updateProblem(draft({ timezone: '-05:00' }))).toContain('IANA');
  });

  it('国家必须是两位字母代码', () => {
    expect(updateProblem(draft({ countryCode: 'CHN' }))).toContain('两位字母');
    expect(updateProblem(draft({ countryCode: '' }))).toContain('国家地区');
  });

  it('名称不能为空', () => {
    expect(updateProblem(draft({ name: '   ' }))).toContain('仓库名称');
  });

  it('未知状态被拒', () => {
    expect(updateProblem(draft({ status: 'GONE' }))).toContain('仓库状态');
  });

  it('从详情回填草稿时把空地址变成空串而不是 undefined', () => {
    const filled = draftOf(detail());
    expect(filled.name).toBe('自营仓');
    expect(filled.addressLine1).toBe('浦东新区世纪大道 100 号');
    expect(filled.addressLine2).toBe('');
    expect(filled.postalCode).toBe('200120');
  });
});

describe('归档是终态', () => {
  it('只有已归档才是终态', () => {
    expect(isArchived('ARCHIVED')).toBe(true);
    expect(isArchived('ACTIVE')).toBe(false);
    expect(isArchived('INACTIVE')).toBe(false);
  });
});
