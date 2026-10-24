package com.starcon.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 13 利润与经营分析服务（蓝图 13 卷「后端归属」：reporting 是经营看板、销量/产品/利润/库存分析、
 * 自定义报表与预警订阅的**唯一**所有者）。
 *
 * <p>服务引导与第一条业务模块 13.1.1.1 经营总览（迁移 V376）同时落地。卷 13 的其余 22 个模块
 * （13.1.1.2 多店对比 / 13.1.2.x 目标管理 / 13.2.x 销量分析 / 13.3.x 产品表现 / 13.4.x 利润分析 /
 * 13.5.x 库存与供应链分析 / 13.6.x 自定义分析）当前全部「未开发」，它们各自会在后续车道里带自己的
 * 迁移与端点。
 *
 * <p>⚠️ **本服务是投影服务，不是写模型服务**（蓝图 13 卷首原文）：
 *
 * <ul>
 *   <li>「不产生业务事实、不做业务写入。报表是投影，<b>可从事件重建</b>；任何报表数字与业务系统冲突时，
 *       <b>以业务系统为准</b>。」
 *   <li>⇒ 本服务**没有任何业务事实的写入口**：唯一允许写的是自己的投影表，且投影随时可从零重建。
 *   <li>⇒ 跨服务一律走**授权 API / event**：不读别的服务的表、不建跨库 FK、不建跨库视图。
 * </ul>
 *
 * <p>上游是 12 财务（finance）、9 订单（trade）、7 库存（warehouse）、14 广告（channel）、
 * 3 商品（catalog）：这些真值都不在本服务，本服务只存**逻辑 ID + 必要快照**（与 trade V1 /
 * warehouse V1 / finance V338 同一纪律）。
 */
@SpringBootApplication
public class ReportingApplication {
  public static void main(String[] args) {
    SpringApplication.run(ReportingApplication.class, args);
  }
}
