'use client';

import { ListingHijackerWorkspace } from '@/components/listing-hijacker/listing-hijacker-workspace';

/**
 * 跟卖监控：L3「4.5.2 跟卖与竞品监控」下的 4.5.2.1。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.5.2.1 跟卖监控</b> —— 监控他人 / 己方其他店铺对本 ASIN 的跟卖行为，
 *       记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。<strong>就是本页</strong>。
 *   <li><b>4.5.2.2 上新与变更监控</b> —— 监控目标 ASIN / 竞品的上下架、价格、排名、
 *       评论数变化，识别市场机会。<strong>尚未交付</strong>。
 * </ul>
 *
 * <p>🔴 页面路由取 <code>/listings/hijackers</code>（跟卖在跨境电商语境里英文是
 * <em>hijacker</em>，抢别人 listing 的人）。与 4.5.1.1 的 <code>/listings/monitor</code>
 * 刻意分开 —— 那是「表现监控」，这页是「跟卖监控」，两条不同的读数。
 *
 * <p>🔴 本模块的数据<b>来自平台抓取 / 平台接口</b>：跟卖者、价格、发现时间全是平台侧的
 * 观测值。页面顶部固定显示来源标注与处置动作声明 —— 蓝图加粗句「跟卖数据只能作为参考，
 * 处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒」是硬要求。
 *
 * <p>🔴 <b>页面上没有「投诉」「举报」按钮。</b> 那些动作在平台侧完成，
 * 本模块只负责发现与提醒；给一个必然 404 的按钮比不给更糟。
 */
export default function ListingHijackerPage() {
  return (
    <div className="space-y-4">
      <ListingHijackerWorkspace />
    </div>
  );
}
