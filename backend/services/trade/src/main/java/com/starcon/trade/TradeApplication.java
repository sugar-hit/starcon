package com.starcon.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 09 订单履约服务（蓝图 09 卷「后端归属」：trade 拥有订单与售后单据）。
 *
 * <p>本次交付的是**服务引导**：进程能起来、数据源/jOOQ/Flyway 接得上、租户上下文接得上。 卷 09 的 27 个 L4 模块一个都还没有落地，因此本类**刻意不注册任何业务
 * Bean** —— 一个空壳服务比「服务还不存在」更危险：它能启动、能回健康检查，却没有任何真实能力。
 */
@SpringBootApplication
public class TradeApplication {
  public static void main(String[] args) {
    SpringApplication.run(TradeApplication.class, args);
  }
}
