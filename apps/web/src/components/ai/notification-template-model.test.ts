import { describe, expect, it } from 'vitest';
import type {
  NotifyTemplateCapabilities,
  NotifyTemplateRenderResult,
  NotifyTemplateVariableDefinition,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';
import {
  formatMoment,
  normalizeLanguage,
  normalizeTemplateKey,
  placeholderNames,
  readRenderResult,
  renderErrorLabel,
  renderErrorTone,
  sceneLabel,
  undeclaredVariables,
  unusedVariables,
  valueInputs,
  variableTypeLabel,
  versionStateLabel,
} from './notification-template-model';

/**
 * 18.5.1.1 模板管理 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **渲染失败要能显示出「缺哪个变量」** —— 400 的响应体就是渲染结果本身，
 *    前端要把它捞出来转成一次正常返回，而不是只显示一句「预览失败」。
 * 2. **正文里用了没声明的变量要当场说出来** —— 服务端会拒收，页面要**先**说一遍。
 * 3. **key 与语言的规范写法** —— key 存的是小写机器名，语言是 BCP-47 规范写法
 *    （zh-cn → zh-CN）；不规范化就会出现「同一个语言存成两条」的版本链。
 * 4. **「不适用」与「已失效」不是一件事** —— 版本状态要分得清。
 */

const capabilities = {
  capabilities: [],
  gaps: [],
  conventions: {
    scenes: [{ code: 'ORDER_STATUS', label: '订单状态变更' }],
    languageRule: '语言写 IANA 形状',
    languageExamples: ['zh-CN'],
    variableTypes: [{ code: 'TEXT', label: '文本' }],
    renderErrorCodes: [],
  },
} as unknown as NotifyTemplateCapabilities;

const definition = (
  name: string,
  type = 'TEXT',
  required = true,
  defaultValue = '',
): NotifyTemplateVariableDefinition => ({ name, type, required, defaultValue, description: '' });

describe('18.5.1.1 模板管理 前端模型', () => {
  it('占位符名字按正文里的顺序提取且去重', () => {
    expect(placeholderNames('标题 {{a}}')).toEqual(['a']);
    expect(placeholderNames('正文 {{b}} {{a}} {{b}}')).toEqual(['b', 'a']);
    expect(placeholderNames('没有占位符')).toEqual([]);
  });

  it('正文里用了没声明的变量当场说出来', () => {
    const declared = [definition('buyer_name')];
    expect(undeclaredVariables('你好 {{buyer_name}} 与 {{nickname}}', declared)).toEqual([
      'nickname',
    ]);
    expect(undeclaredVariables('你好 {{buyer_name}}', declared)).toEqual([]);
  });

  it('声明了但正文里没用的变量只是提醒', () => {
    const declared = [definition('buyer_name'), definition('remark')];
    expect(unusedVariables('你好 {{buyer_name}}', declared)).toEqual(['remark']);
    expect(unusedVariables('你好 {{buyer_name}} {{remark}}', declared)).toEqual([]);
  });

  it('模板 key 规范化成小写机器名', () => {
    expect(normalizeTemplateKey('  ORDER_PAID ')).toBe('order_paid');
  });

  it('语言规范化成 BCP-47 规范写法', () => {
    expect(normalizeLanguage('zh-cn')).toBe('zh-CN');
    expect(normalizeLanguage('EN')).toBe('en');
    expect(normalizeLanguage('pt-br')).toBe('pt-BR');
    expect(normalizeLanguage('zh-hans')).toBe('zh-Hans');
  });

  it('场景与变量类型的人话来自后端词表，不是前端写死的', () => {
    expect(sceneLabel(capabilities, 'ORDER_STATUS')).toBe('订单状态变更');
    expect(sceneLabel(capabilities, 'UNKNOWN_SCENE')).toBe('UNKNOWN_SCENE');
    expect(variableTypeLabel(capabilities, 'TEXT')).toBe('文本');
    expect(variableTypeLabel(capabilities, 'WHATEVER')).toBe('WHATEVER');
  });

  it('渲染错误码有人话，且语法错误与未声明变量是硬伤', () => {
    expect(renderErrorLabel('MISSING_VARIABLE')).toBe('缺少变量取值');
    expect(renderErrorLabel('UNKNOWN_VARIABLE')).toBe('未声明的变量');
    expect(renderErrorLabel('SYNTAX_ERROR')).toBe('模板语法错误');
    expect(renderErrorTone('SYNTAX_ERROR')).toBe('danger');
    expect(renderErrorTone('UNKNOWN_VARIABLE')).toBe('danger');
    expect(renderErrorTone('MISSING_VARIABLE')).toBe('warn');
    expect(renderErrorTone('EXTRA_VARIABLE')).toBe('warn');
  });

  it('版本状态分得清：生效中 / 已失效 / 未启用', () => {
    expect(
      versionStateLabel({ active: true, activatedAt: '2026-11-01T00:00:00Z', deactivatedAt: null }),
    ).toBe('生效中');
    expect(
      versionStateLabel({
        active: false,
        activatedAt: '2026-11-01T00:00:00Z',
        deactivatedAt: '2026-11-02T00:00:00Z',
      }),
    ).toBe('已失效');
    expect(versionStateLabel({ active: false, activatedAt: null, deactivatedAt: null })).toBe(
      '未启用',
    );
  });

  it('渲染取值表按变量定义逐条列出', () => {
    const inputs = valueInputs(
      [definition('buyer_name'), definition('order_no', 'TEXT', false, 'SO-1')],
      { buyer_name: '小明' },
    );
    expect(inputs).toEqual([
      { name: 'buyer_name', type: 'TEXT', value: '小明' },
      { name: 'order_no', type: 'TEXT', value: '' },
    ]);
  });

  it('400 的渲染结果能被捞出来（页面因此能显示「缺哪个变量」）', () => {
    const body = {
      ok: false,
      message: '缺少变量取值：order_no',
      renderedSubject: null,
      renderedBody: null,
      variables: [],
      errors: [
        {
          code: 'MISSING_VARIABLE',
          message: '缺少变量取值：order_no',
          variables: ['order_no'],
        },
      ],
      templateId: null,
      versionId: null,
      templateKey: null,
      language: null,
      versionNo: null,
    } satisfies NotifyTemplateRenderResult;
    const result = readRenderResult(new ApiError(400, body.message, body));
    expect(result).not.toBeNull();
    expect(result?.ok).toBe(false);
    expect(result?.errors[0]?.variables).toEqual(['order_no']);
    expect(result?.renderedBody).toBeNull();
  });

  it('不是渲染结果的错误原样抛出（不吞掉真错误）', () => {
    expect(readRenderResult(new Error('网络断了'))).toBeNull();
    expect(readRenderResult(new ApiError(500, '服务端炸了', { detail: 'boom' }))).toBeNull();
  });

  it('时刻显示成可读文本，空值显示成占位符而不是 Invalid Date', () => {
    expect(formatMoment(null)).toBe('—');
    expect(formatMoment('')).toBe('—');
    expect(formatMoment('2026-11-02T10:00:00+08:00')).not.toContain('Invalid');
  });
});
