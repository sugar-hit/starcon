package com.starcon.channel.oauth;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 2.2.1.2 写 {@code timestamptz} 列的唯一入口。
 *
 * <p>jOOQ 3.19 绑 timestamptz 时只有两种类型能写成功：{@code java.sql.Timestamp} 与裸 {@code
 * LocalDateTime}。Instant / OffsetDateTime / ISO 字符串一律被绑成 varchar 而被 PG 拒收。
 *
 * <p>但裸 {@code LocalDateTime} 是<b>陷阱</b>：jOOQ 会按 <b>JVM 默认时区</b>把它解释成一个绝对时刻， 而不是按 PG 会话时区。在
 * Asia/Shanghai 的机器上，写入会比预期早 8 小时——授权会话写进去就是 "刚创建就已过期"。所以这里只允许用 {@link Timestamp}，它在内部持有 epoch 毫秒，
 * 与 JVM 时区无关，往返精确到秒。
 */
final class Timestamps {
  private Timestamps() {}

  /** null 安全：到期时间可空，返回 null 让 SQL 写 NULL。 */
  static Timestamp at(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
