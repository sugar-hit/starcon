package com.starcon.catalog;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 店铺站点 → 语言推导端口的当前实现：02 渠道尚未接入 catalog。
 *
 * <p>{@link #derivedLocales} 一律返回 {@code available=false} 且 rows 为空， 并写明归 02
 * 渠道。语言清单页据此把「站点推导」那一栏标成「未接入」， 而不是显示一个空的语言下拉框：
 *
 * <ul>
 *   <li>空下拉框 + 「无需多语言」的暗示，会让运营以为这个店铺根本不需要做多语言；
 *   <li>回 0 会让完整度报表显示「0 种语言需求」，而这是把「取不到」说成了「不需要」。
 * </ul>
 *
 * <p>本模块因此同时提供 {@code translation_locale} 的**人工维护**一侧： 那是现在就能真实写入、真实校验的数据来源，不是等 02 渠道的占位。
 *
 * <p>02 渠道落地后用真实适配器替换本 Bean 即可，合并视图与语言清单页面无需改动。
 */
@Component
public class UnwiredTranslationLocaleProjectionPort implements TranslationLocaleProjectionPort {

  @Override
  public Projection derivedLocales(UUID tenantId) {
    return Projection.unwired(unwiredNote());
  }
}
