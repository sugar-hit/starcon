'use client';

import { ListingChangeWorkspace } from '@/components/listing-change/listing-change-workspace';

/**
 * 上新与变更监控：L3「4.5.2 跟卖与竞品监控」下的 4.5.2.2。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.5.2.1 跟卖监控</b> —— 监控他人 / 己方其他店铺对本 ASIN 的跟卖行为，
 *       记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。
 *       页面在 <code>/listings/hijackers</code>。</li>
 *   <li><b>4.5.2.2 上新与变更监控</b> —— 监控目标 ASIN / 竞品的上下架、价格、排名、
 *       评论数变化，识别市场机会（如竞品断货涨价）。<strong>就是本页</strong>。</li>
 * </ul>
 *
 * <p>🔴 页面路由取 <code>/listings/changes</code>，与 4.5.2.1 的
 * <code>/listings/hijackers</code> 刻意分开 —— 那是「盯我的商品有没有被跟卖」，
 * 这页是「盯竞品有没有变」，两条不同的读数、不同的数据边界。
 *
 * <p>🔴 <b>本模块的数据全部来自平台抓取 / 平台接口</b>：竞品的价格、排名、评论数、
 * 上下架状态全是平台侧的观测值。页面顶部固定显示来源标注与动作声明 ——
 * 蓝图加粗句「<b>监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标</b>」
 * 是硬要求。
 *
 * <p>🔴 <b>页面上没有「抢占」「调价」按钮。</b> 竞品数据只做参考指标，
 * 动作在平台侧或自有定价流程里完成；给一个必然 404 的按钮比不给更糟。
 *
 * <p>🔴 监控对象只有「平台 + ASIN」这一对身份，<b>页面上也没有「关联我方商品」这种入口</b> ——
 * 那是把竞品数据挂回自有商品模型的路，本模块明确不走。
 */
export default function ListingChangesPage() {
  return (
    <div className="space-y-4">
      <ListingChangeWorkspace />
    </div>
  );
}
