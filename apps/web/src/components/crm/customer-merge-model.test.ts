import { describe, expect, it } from 'vitest';
import type {
  CrmCustomerMergeAmount,
  CrmCustomerMergeCandidatePage,
  CrmCustomerMergeSignal,
} from '../../../../../packages/api-contracts/src/index';
import {
  customerMergeAmountText,
  customerMergeCandidateNote,
  customerMergeConfidenceText,
  customerMergeEmptyStateText,
  customerMergeExclusionFlagLabel,
  customerMergeMatchTypeLabel,
  customerMergeOptOutLabel,
  customerMergeRunText,
  customerMergeSignalText,
  customerMergeStatusLabel,
} from './customer-merge-model';

/**
 * 15.3.1.3 跨店合并前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>「只给建议」与「已经合并」是两句话</b>：把 SUGGESTED 画成「已合并」，
 *       运营会以为点一下扫描就合并了 —— 而蓝图明确说自动合并风险高。</li>
 *   <li><b>置信度要能解释</b>：只给一个分数不叫可解释；逐条信号 + 比对值才是。</li>
 *   <li><b>金额按币种分行</b>：USD 与 EUR 加起来不是任何一个金额，
 *       页面把两个币种合成一个数就是造数据。</li>
 *   <li><b>排除信号各有各的话</b>：OPT_OUT 不是普通备注 ——
 *       合并会让所有来源继承这个退订。</li>
 *   <li><b>空态分两种</b>：「筛选无结果」与「还没生成过候选」要的行动完全不同。</li>
 * </ol>
 */
describe('15.3.1.3 跨店合并前端模型', () => {
  describe('候选状态的可读名', () => {
    it('「待确认」与「已合并」是**两句话**，不能混', () => {
      expect(customerMergeStatusLabel('SUGGESTED')).toBe('待确认');
      expect(customerMergeStatusLabel('CONFIRMED')).toBe('已合并');
      expect(customerMergeStatusLabel('SUGGESTED')).not.toBe(customerMergeStatusLabel('CONFIRMED'));
    });

    it('其余状态各有各的话', () => {
      expect(customerMergeStatusLabel('REJECTED')).toBe('已拒绝');
      expect(customerMergeStatusLabel('STALE')).toBe('已失效');
    });

    it('未知状态原样返回（不吞掉后端的新状态）', () => {
      expect(customerMergeStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(customerMergeStatusLabel(undefined)).toBe('');
    });
  });

  describe('匹配依据的可读名', () => {
    it('四级信号的名字都能显示出来', () => {
      expect(customerMergeMatchTypeLabel('PLATFORM_BUYER_ID')).toBe('平台买家标识相同');
      expect(customerMergeMatchTypeLabel('EMAIL')).toBe('邮箱相同');
      expect(customerMergeMatchTypeLabel('PHONE')).toBe('电话相同');
      expect(customerMergeMatchTypeLabel('NAME_ADDRESS')).toBe('姓名与地址都相同');
    });

    it('未知依据原样返回', () => {
      expect(customerMergeMatchTypeLabel('WAT')).toBe('WAT');
      expect(customerMergeMatchTypeLabel(undefined)).toBe('');
    });
  });

  describe('置信度', () => {
    it('原样显示四位小数（前端不做任何算术）', () => {
      expect(customerMergeConfidenceText(0.9)).toBe('0.9');
      expect(customerMergeConfidenceText(0.6)).toBe('0.6');
    });

    it('缺失时是「—」，不是 0（0 会被读成「置信度为零」）', () => {
      expect(customerMergeConfidenceText(null)).toBe('—');
      expect(customerMergeConfidenceText(undefined)).toBe('—');
      expect(customerMergeConfidenceText(null)).not.toContain('0');
    });
  });

  describe('信号明细', () => {
    const signals: CrmCustomerMergeSignal[] = [
      {
        signalType: 'EMAIL',
        label: '邮箱相同',
        matched: true,
        strength: 0.85,
        maskedValue: 'a***@***.com',
        detail: '两侧邮箱归一化后完全相同',
      },
      {
        signalType: 'PLATFORM_BUYER_ID',
        label: '平台买家标识相同',
        matched: true,
        strength: 0.95,
        maskedValue: '',
        detail: '两个来源店铺指向同一个 catalog 客户档案',
      },
    ];

    it('逐条列出命中了哪个信号、强度多少、比对值是什么', () => {
      const text = customerMergeSignalText(signals);
      expect(text).toContain('邮箱相同');
      expect(text).toContain('0.85');
      expect(text).toContain('a***@***.com');
      expect(text).toContain('平台买家标识相同');
      expect(text).toContain('0.95');
    });

    it('一条信号都没有时明说「没有命中」，而不是给一个空串', () => {
      expect(customerMergeSignalText([])).toBe('没有任何信号命中');
      expect(customerMergeSignalText(undefined)).toBe('没有任何信号命中');
    });

    it('没有比对值的信号不画出一个空的括号', () => {
      const text = customerMergeSignalText([signals[1]!]);
      expect(text).toContain('平台买家标识相同');
      expect(text).not.toContain('（）');
    });
  });

  describe('排除信号', () => {
    it('OPT_OUT 有自己的一句话 —— 合并会让所有来源继承这个退订', () => {
      expect(customerMergeExclusionFlagLabel('OPT_OUT')).toContain('所有来源');
      expect(customerMergeExclusionFlagLabel('OPT_OUT')).not.toBe(
        customerMergeExclusionFlagLabel('CONFLICTING_NAME'),
      );
    });

    it('其余排除信号各归各', () => {
      expect(customerMergeExclusionFlagLabel('CONFLICTING_NAME')).toContain('姓名');
      expect(customerMergeExclusionFlagLabel('ADDRESS_UNAVAILABLE')).toContain('地址');
      expect(customerMergeExclusionFlagLabel('NO_ADDRESS')).toContain('地址');
      expect(customerMergeExclusionFlagLabel('UNKNOWN')).toBe('UNKNOWN');
    });
  });

  describe('退订状态', () => {
    it('三态各有各的话', () => {
      expect(customerMergeOptOutLabel('NONE')).toBe('无退订记录');
      expect(customerMergeOptOutLabel('ACTIVE')).toBe('退订生效中');
      expect(customerMergeOptOutLabel('REVOKED')).toBe('退订已撤销');
    });
  });

  describe('金额按币种分行', () => {
    const amounts: CrmCustomerMergeAmount[] = [
      { currency: 'EUR', totalAmount: 20, orderCount: 2, avgOrderAmount: 10 },
      { currency: 'USD', totalAmount: 100.1, orderCount: 3, avgOrderAmount: 33.3667 },
    ];

    it('一行一个币种，**不做任何加法**', () => {
      const text = customerMergeAmountText(amounts);
      expect(text).toContain('EUR 20');
      expect(text).toContain('USD 100.1');
      // 🔴 绝不能出现 120.1 这种「把两个币种加起来」的数字。
      expect(text).not.toContain('120.1');
    });

    it('客单价在订单数为 0 时是「—」（不可判定），不是 0', () => {
      const text = customerMergeAmountText([
        { currency: 'JPY', totalAmount: 0, orderCount: 0, avgOrderAmount: null },
      ]);
      expect(text).toContain('客单价 —');
    });

    it('没有消费记录时明说「无消费记录」', () => {
      expect(customerMergeAmountText([])).toBe('无消费记录');
      expect(customerMergeAmountText(undefined)).toBe('无消费记录');
    });
  });

  describe('空态分两种', () => {
    it('「筛选无结果」与「还没生成过候选」要的行动完全不同', () => {
      const filtered = customerMergeEmptyStateText(true, true);
      const fresh = customerMergeEmptyStateText(false, false);
      expect(filtered.title).not.toBe(fresh.title);
      expect(fresh.description).toContain('只给建议');
      expect(fresh.description).toContain('不会合并');
    });

    it('跑过但没有产出，也要说清是「跑过了」而不是「没跑过」', () => {
      const ran = customerMergeEmptyStateText(false, true);
      expect(ran.description).toContain('上一次生成候选');
      expect(ran.description).not.toContain('点「生成候选建议」');
    });
  });

  describe('候选队列说明', () => {
    it('三种状态各有计数，且带上「只是建议」的提示', () => {
      const page = {
        rows: [],
        page: 0,
        size: 20,
        total: 0,
        totalPages: 0,
        suggestedCount: 3,
        confirmedCount: 1,
        rejectedCount: 2,
        note: '候选是建议。',
      } as unknown as CrmCustomerMergeCandidatePage;
      const note = customerMergeCandidateNote(page);
      expect(note).toContain('待确认 3');
      expect(note).toContain('已合并 1');
      expect(note).toContain('已拒绝 2');
      expect(note).toContain('建议');
    });

    it('页面还没加载时返回空串（不炸）', () => {
      expect(customerMergeCandidateNote(undefined)).toBe('');
    });
  });

  describe('运行台账摘要', () => {
    it('如实报出扫了多少、砍掉了多少、截断没有', () => {
      const text = customerMergeRunText({
        runNo: 'CM-20261102-000001',
        status: 'SUCCEEDED',
        scannedCustomerCount: 300,
        candidateCount: 7,
        belowThresholdCount: 12,
        excludedSameShopCount: 3,
        excludedAlreadyMergedCount: 1,
        optOutCandidateCount: 2,
        addressResolvedCount: 280,
        addressUnavailableCount: 20,
        truncated: true,
        failureReason: '',
      });
      expect(text).toContain('CM-20261102-000001');
      expect(text).toContain('扫了 300 位');
      expect(text).toContain('产出候选 7 条');
      expect(text).toContain('同名不同人');
      expect(text).toContain('截断');
    });

    it('没有运行时返回空串', () => {
      expect(customerMergeRunText(undefined)).toBe('');
    });
  });
});
