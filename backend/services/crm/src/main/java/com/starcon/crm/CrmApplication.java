package com.starcon.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 15 客户与客服服务（蓝图 15 卷「后端归属」：crm 拥有买家消息、评价、工单、知识库与触达记录）。
 *
 * <p>服务引导与第一条业务端点 15.4.2.1 知识库同时落地。其余卷 15 模块
 * （15.1.1.1 消息收件箱 / 15.1.1.2 消息发送 / 15.4.1.x 工单 / 15.5.1.1 自动回复 …）
 * 当前全部「未开发」，它们各自会在后续车道里带自己的迁移与端点。
 *
 * <p>跨服务一律走授权 API / event：店铺与平台属 channel、类目与 SKU 属 catalog、
 * 订单属 trade，本服务只存逻辑 ID 与必要快照（与 trade V1 / warehouse V1 同一纪律），
 * 不建跨库外键、跨库视图或任何读上游库的 SQL。
 */
@SpringBootApplication
public class CrmApplication {
  public static void main(String[] args) {
    SpringApplication.run(CrmApplication.class, args);
  }
}
