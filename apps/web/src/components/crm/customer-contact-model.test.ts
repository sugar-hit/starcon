import { describe, expect, it } from 'vitest';
import {
  customerContactComplianceLabel,
  customerContactDetail,
  customerContactDirectionLabel,
  customerContactDurationText,
  customerContactOriginLabel,
  customerContactOptOutEventLabel,
  customerContactOptOutScopeLabel,
  customerContactOptOutStateLabel,
  customerContactSuppressedText,
  customerContactTypeLabel,
} from './customer-contact-model';

/**
 * 15.3.2.2 接触记录前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>「不可营销」与「可以营销」必须是两句话</b>：把退订画成「未标记」，
 *       会让「他明确说过不要再联系我」看起来像一条可选备注 ——
 *       而它恰恰是本模块要交给 15.5.2 的<b>豁免依据</b>。</li>
 *   <li><b>退订不是一个接触类型</b>：补录表单里的类型清单**不含** OPT_OUT，
 *       退订只能走显式的退订端点（普通接触记录改不了它）。</li>
 *   <li><b>时长不在前端换算</b>：秒数由后端给，页面只原样显示 ——
 *       各处自己换算成「3 分 05 秒」，口径迟早会不一致。</li>
 *   <li><b>来源必须分得清</b>：手工补录与上游模块自动写入是两类记录。</li>
 * </ol>
 */
describe('15.3.2.2 接触记录前端模型', () => {
  describe('豁免判定的可读形态', () => {
    it('「不可营销」与「可以营销」是**两句话**，不能混', () => {
      expect(customerContactSuppressedText(true)).toBe('不可营销');
      expect(customerContactSuppressedText(false)).toBe('可以营销');
      expect(customerContactSuppressedText(true)).not.toBe(customerContactSuppressedText(false));
      // 关键：那句「不可营销」里必须出现「不可」两个字 ——
      //   画成「可营销」会被读成「可以营销」的省略，理解就反了。
      expect(customerContactSuppressedText(true)).toContain('不可');
      expect(customerContactSuppressedText(false)).toContain('可以');
    });
  });

  describe('接触类型', () => {
    it('蓝图点名的五类都有可读名', () => {
      expect(customerContactTypeLabel('CALL')).toBe('客服通话');
      expect(customerContactTypeLabel('MESSAGE')).toBe('消息');
      expect(customerContactTypeLabel('EMAIL')).toBe('邮件');
      expect(customerContactTypeLabel('MARKETING')).toBe('营销触达');
      expect(customerContactTypeLabel('AFTERSALES')).toBe('售后沟通');
    });

    it('退订是合规状态，但类型表里**有**它（写出来时必须分得清）', () => {
      expect(customerContactTypeLabel('OPT_OUT')).toBe('退订 / 免打扰');
      expect(customerContactTypeLabel('OPT_OUT')).not.toBe(customerContactTypeLabel('OTHER'));
    });
  });

  describe('方向 / 来源 / 合规状态', () => {
    it('方向分得清客户发起与我们发起', () => {
      expect(customerContactDirectionLabel('INBOUND')).toBe('客户发起');
      expect(customerContactDirectionLabel('OUTBOUND')).toBe('我们发起');
      expect(customerContactDirectionLabel('INBOUND')).not.toBe(
        customerContactDirectionLabel('OUTBOUND'),
      );
    });

    it('来源分得清手工补录与自动写入', () => {
      expect(customerContactOriginLabel('MANUAL')).toBe('手工补录');
      expect(customerContactOriginLabel('AUTO')).toBe('自动写入');
      expect(customerContactOriginLabel('MANUAL')).not.toBe(customerContactOriginLabel('AUTO'));
    });

    it('违规留痕必须有单独的说法', () => {
      expect(customerContactComplianceLabel('COMPLIANT')).toBe('合规');
      expect(customerContactComplianceLabel('VIOLATION')).toBe('违反退订豁免');
      expect(customerContactComplianceLabel('VIOLATION')).toContain('退订');
    });
  });

  describe('退订的范围 / 状态 / 审计事件', () => {
    it('范围分得清「全部触达」与「仅营销触达」', () => {
      expect(customerContactOptOutScopeLabel('ALL')).toBe('全部触达');
      expect(customerContactOptOutScopeLabel('MARKETING')).toBe('仅营销触达');
      expect(customerContactOptOutScopeLabel('ALL')).not.toBe(
        customerContactOptOutScopeLabel('MARKETING'),
      );
    });

    it('状态分生效中与已撤销', () => {
      expect(customerContactOptOutStateLabel('ACTIVE')).toBe('生效中');
      expect(customerContactOptOutStateLabel('REVOKED')).toBe('已撤销');
    });

    it('审计事件分登记与撤销', () => {
      expect(customerContactOptOutEventLabel('GRANTED')).toBe('登记退订');
      expect(customerContactOptOutEventLabel('REVOKED')).toBe('撤销退订');
    });
  });

  describe('时长不在前端算', () => {
    it('秒数原样显示，不做「分秒」换算', () => {
      expect(customerContactDurationText(183.5)).toBe('183.5 秒');
      expect(customerContactDurationText(183.5)).not.toContain('分');
      // 没有时长就说没有，不显示 0 秒。
      expect(customerContactDurationText(null)).toBe('');
      expect(customerContactDurationText(undefined)).toBe('');
    });
  });

  describe('补充信息快照', () => {
    it('空快照不该炸，也不该编出依据', () => {
      expect(customerContactDetail(null)).toEqual({});
      expect(customerContactDetail(undefined)).toEqual({});
      expect(customerContactDetail({ messageNo: 'MSG-1' })).toEqual({ messageNo: 'MSG-1' });
    });
  });
});
