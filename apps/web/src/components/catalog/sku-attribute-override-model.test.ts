import { describe, expect, it } from 'vitest';
import type { SkuOverrideAttribute } from '../../../../../packages/api-contracts/src/catalog';
import {
  actionable,
  clearOverride,
  effectiveText,
  errorText,
  isNotFound,
  isRequiredMissing,
  missingFromError,
  missingHint,
  overridePath,
  removeOverride,
  setOverride,
  sourceLabel,
  sourceTone,
  writable,
} from './sku-attribute-override-model';
import { ApiError } from './api';

/**
 * 3.2.2.2 展示语义的可测部分。
 *
 * <p>重点盯两件事：**四态各有各的说法**，以及**结构化缺失清单**能被取出来逐条显示。
 * 这两处一旦退化成"一律显示未填写"或"只显示一句 message"，用户就分辨不出
 * 「这个 SKU 确实没有该属性」与「还没人填」，也不知道该去补哪几条。
 */

function attribute(over: Partial<SkuOverrideAttribute> = {}): SkuOverrideAttribute {
  return {
    attributeId: 'a-1',
    code: 'COLOR',
    name: '颜色',
    dataType: 'OPTION',
    valueMode: 'COMMON_DEFAULT',
    valueModeLabel: '公共默认 · 可覆写',
    multiple: false,
    required: false,
    categoryRequired: false,
    variantAxis: false,
    unit: '',
    groupName: '基本参数',
    options: ['红', '蓝'],
    overrideState: 'INHERIT',
    source: 'SPU_COMMON',
    sourceLabel: 'SPU 公共值',
    rawValues: [],
    effectiveValues: ['红'],
    inherited: true,
    missingRequired: false,
    overridable: true,
    blockReason: null,
    overrideUpdatedAt: null,
    ...over,
  };
}

describe('来源四态各有各的说法', () => {
  it('四态的中文标签互不相同，UNKNOWN 才退回原样', () => {
    expect(sourceLabel(attribute({ source: 'SKU_OVERRIDE' }))).toBe('SKU 覆写');
    expect(sourceLabel(attribute({ source: 'SKU_CLEARED' }))).toBe('SKU 已清空');
    expect(sourceLabel(attribute({ source: 'SPU_COMMON' }))).toBe('SPU 公共值');
    expect(sourceLabel(attribute({ source: 'UNSET' }))).toBe('未设置');
    // 后端加了新来源时不会白屏，但也绝不把未知来源说成"未设置"
    expect(sourceLabel(attribute({ source: 'SOMETHING_NEW' }))).toBe('SOMETHING_NEW');
  });

  it('tone 跟着语义走：覆写与继承分开，清空与未设置都偏警告', () => {
    expect(sourceTone(attribute({ source: 'SKU_OVERRIDE' }))).toBe('info');
    expect(sourceTone(attribute({ source: 'SPU_COMMON' }))).toBe('neutral');
    expect(sourceTone(attribute({ source: 'SKU_CLEARED' }))).toBe('warning');
    expect(sourceTone(attribute({ source: 'UNSET' }))).toBe('warning');
  });
});

describe('有效值显示', () => {
  it('有值时返回显示文本', () => {
    expect(effectiveText(attribute({ effectiveValues: ['蓝'] }))).toBe('蓝');
    expect(effectiveText(attribute({ effectiveValues: ['红', '蓝'] }))).toBe('红 / 蓝');
  });

  it('没有值时返回 null 而不是空串：空串会渲染成一个看不出所以然的空格', () => {
    expect(effectiveText(attribute({ effectiveValues: [] }))).toBeNull();
    expect(effectiveText(attribute({ source: 'SKU_CLEARED', effectiveValues: [] }))).toBeNull();
  });

  it('空值不等于空串：值是空串也要照实显示', () => {
    expect(effectiveText(attribute({ effectiveValues: [''] }))).toBe('');
  });
});

describe('必填缺口文案', () => {
  it('区分"被显式清空"与"从未设置"，两者的下一步动作不同', () => {
    expect(missingHint(attribute({ source: 'SKU_CLEARED', missingRequired: true }))).toContain(
      '显式清空',
    );
    expect(missingHint(attribute({ source: 'UNSET', missingRequired: true }))).toContain(
      '还没有生效值',
    );
  });
});

describe('筛选与权限', () => {
  it('actionable 覆盖"改过"与"缺值"两类，其余只在"只看要处理的"时出现', () => {
    expect(actionable(attribute({ source: 'SKU_OVERRIDE' }))).toBe(true);
    expect(actionable(attribute({ source: 'SKU_CLEARED' }))).toBe(true);
    expect(actionable(attribute({ source: 'UNSET' }))).toBe(true);
    expect(actionable(attribute({ source: 'SPU_COMMON' }))).toBe(false);
    // 继承但缺必填的仍要出现在列表里，否则用户看不到缺口
    expect(actionable(attribute({ source: 'SPU_COMMON', missingRequired: true }))).toBe(true);
  });

  it('写权限按 catalog.attribute.update 收敛', () => {
    expect(writable(['catalog.attribute.read'])).toBe(false);
    expect(writable(['catalog.attribute.read', 'catalog.attribute.update'])).toBe(true);
  });
});

describe('错误分类', () => {
  it('404 表示 SKU 不存在或当前租户看不到', () => {
    expect(isNotFound(new ApiError(404, '记录不存在或没有访问权限'))).toBe(true);
    expect(isNotFound(new ApiError(400, '参数不对'))).toBe(false);
    expect(isNotFound(new Error('网络断了'))).toBe(false);
  });

  it('必填校验未通过时取出结构化缺失清单', () => {
    const error = new ApiError(400, '类目必填属性缺少生效值', {
      missing: [
        {
          attributeId: 'a-1',
          code: 'WEIGHT',
          name: '重量',
          source: 'UNSET',
          reason: '必填属性还没有生效值',
        },
      ],
      missingCount: 1,
    });
    expect(isRequiredMissing(error)).toBe(true);
    const missing = missingFromError(error);
    expect(missing).toHaveLength(1);
    expect(missing[0]?.name).toBe('重量');
  });

  it('没有清单时返回空数组，界面据此区分"有清单要逐条显示"与"只是一句提示"', () => {
    expect(missingFromError(new ApiError(400, '参数不对', {}))).toEqual([]);
    expect(missingFromError(new ApiError(400, '参数不对'))).toEqual([]);
    expect(missingFromError(null)).toEqual([]);
    // 400 但没有清单：不算"必填校验未通过"，不能渲染成缺失清单
    expect(isRequiredMissing(new ApiError(400, '参数不对', {}))).toBe(false);
  });

  it('errorText 优先用服务端给的那句话', () => {
    expect(errorText(new Error('属性「重量」是必填属性'), '保存失败')).toContain('必填');
    expect(errorText(null, '保存失败')).toBe('保存失败');
  });
});

describe('端点路径', () => {
  it('按 SKU 寻址，不要求前端先查商品详情拿 SPU id', () => {
    expect(overridePath('sku-1')).toBe('catalog/sku-attribute-override/skus/sku-1');
    expect(overridePath('sku-1')).not.toContain('products');
  });

  it('三个写动作是三个不同端点：清空与取消覆写方向相反，不能共用一个 state 字段', () => {
    // 这里只验证"三个动作确实是三个独立导出"，不真的发请求：
    // 路径形状由后端契约与 api-proxy 的 allowlist 测试一起钉住。
    expect(typeof setOverride).toBe('function');
    expect(typeof clearOverride).toBe('function');
    expect(typeof removeOverride).toBe('function');
    expect(setOverride).not.toBe(clearOverride);
    expect(clearOverride).not.toBe(removeOverride);
  });
});
