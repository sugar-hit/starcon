import { describe, expect, it } from 'vitest';
import {
  continuousLabel,
  declineLabel,
  dimensionLabel,
  productReviewQuery,
  productReviewStatsQuery,
  ratingLabel,
  replyStateLabel,
  reviewSourceLabel,
} from './product-review-model';

/**
 * 15.2.1.1 评价列表前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>筛选条件不能丢</b>：列表、统计与导出走的是**同一套**筛选条件 ——
 *       「页面上看到的」与「导出的」必须是同一批评价，否则导出会被当成另一份事实。</li>
 *   <li><b>「连续下滑」不能由前端自己说</b>：只有**三窗单调**才算连续，
 *       判定由后端给出（{@code continuous}），前端只原样展示。</li>
 *   <li><b>下滑必须能定位到维度</b>：标题里一定要带上<b>键</b> ——
 *       只显示名字的滑���定位等于没定位。</li>
 * </ol>
 */
describe('15.2.1.1 评价列表前端模型', () => {
  describe('筛选条件', () => {
    it('布尔筛选只在为真时出现，为假时不下参数', () => {
      expect(productReviewQuery({ hasImage: true })).toBe('?hasImage=true');
      expect(productReviewQuery({ hasImage: false })).toBe('');
      expect(productReviewQuery({ hasVideo: true, unreplied: true })).toBe(
        '?hasVideo=true&unreplied=true',
      );
    });

    it('星级 0 不被当成「没填」—— 它是合法取值（虽然后端会拒）', () => {
      expect(productReviewQuery({ rating: 0 })).toBe('?rating=0');
      expect(productReviewQuery({ rating: 5 })).toBe('?rating=5');
    });

    it('分页参数与筛选参数都在同一串里', () => {
      const query = productReviewQuery({ shopId: 'shop-1', page: 2, size: 20 });
      expect(query).toContain('shopId=shop-1');
      expect(query).toContain('page=2');
      expect(query).toContain('size=20');
    });

    it('空筛选不产生问号', () => {
      expect(productReviewQuery()).toBe('');
      expect(productReviewQuery({})).toBe('');
    });

    it('统计查询串带 days，且空白筛选时不产出「?&」畸形串', () => {
      expect(productReviewStatsQuery({}, 30)).toBe('?days=30');
      expect(productReviewStatsQuery({ rating: 3 }, 7)).toBe('?rating=3&days=7');
      // 分页参数不该进统计查询（统计与分页无关）。
      expect(productReviewStatsQuery({ page: 2, size: 20 }, 30)).toBe('?days=30');
    });
  });

  describe('可读名', () => {
    it('「已回复 / 未回复」两种说法不能混', () => {
      expect(replyStateLabel(true)).toBe('已回复');
      expect(replyStateLabel(false)).toBe('未回复');
      expect(replyStateLabel(true)).not.toBe(replyStateLabel(false));
    });

    it('星级与来源各有各的说法', () => {
      expect(ratingLabel(4)).toBe('4 星');
      expect(reviewSourceLabel('MANUAL_ENTRY')).toBe('人工登记');
      expect(reviewSourceLabel('PLATFORM_SYNC')).toBe('平台同步');
      // 未知取值原样透出，不编一个像模像样的说法。
      expect(reviewSourceLabel('WHATEVER')).toBe('WHATEVER');
    });

    it('三个下滑维度各有各的说法', () => {
      expect(dimensionLabel('SHOP')).toBe('店铺');
      expect(dimensionLabel('PRODUCT')).toBe('商品');
      expect(dimensionLabel('VARIANT')).toBe('变体');
      expect(dimensionLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('下滑定位', () => {
    it('标题里必须带键 —— 否则客服看完不知道去查哪一条', () => {
      const label = declineLabel({ dimension: 'VARIANT', key: 'SKU-DOWN', label: '蓝色 XL' });
      expect(label).toContain('变体');
      expect(label).toContain('蓝色 XL');
      expect(label).toContain('SKU-DOWN');
    });

    it('名字缺失时用键兜底，不能渲染成空', () => {
      const label = declineLabel({ dimension: 'PRODUCT', key: 'SPU-9', label: '' });
      expect(label).toContain('SPU-9');
      expect(label).toContain('未命名');
    });

    it('「连续下滑」与「下滑」是两种说法，不能都叫连续', () => {
      expect(continuousLabel(true)).toBe('连续下滑（三窗单调）');
      expect(continuousLabel(false)).toBe('下滑（尚未确认连续）');
      expect(continuousLabel(true)).not.toBe(continuousLabel(false));
    });
  });
});
