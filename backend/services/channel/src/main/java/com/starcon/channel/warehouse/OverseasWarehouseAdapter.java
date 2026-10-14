package com.starcon.channel.warehouse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 10.4.2.1 海外仓服务商适配层。
 *
 * <p>本仓库**没有真实的海外仓服务商账号与网络出口**，因此这里刻意只定义边界，不放任何 指向真实域名的 HTTP 客户端。将来接真实服务商时，在这里新增一个 vendor 实现即可，
 * 上层服务与数据模型不需要改动。
 *
 * <p>纪律（与 02 卷 {@code PlatformOAuthAdapter} 同一套）：
 *
 * <ol>
 *   <li>适配器**只负责取数**，不负责落库——落库统一由服务层做，所以「观测值只进观测表」 这条不变量在适配器层就无法被绕过（适配器根本拿不到写自营库存的句柄）
 *   <li>观测时点必须由适配器从服务商响应里带出来，不能用本地时钟冒充
 *   <li>凭据只从 {@link ConnectionSettings} 取引用，适配器内部若需解密由具体实现负责， 但**返回值里不允许出现凭据**
 * </ol>
 */
public interface OverseasWarehouseAdapter {

  /** 服务商代码，与 {@code warehouse_provider.code} 对应。 */
  String providerCode();

  /** 服务商侧的连接参数。凭据是 secret manager 引用，不是明文。 */
  record ConnectionSettings(
      UUID connectionId,
      UUID accountId,
      String authorizedAccount,
      String externalAccountCode,
      String credentialExternalRef,
      String apiEndpointRef) {}

  /** 一条库存观测。由适配器取回，交给服务层落成观测快照。 */
  record StockLine(String externalSku, BigDecimal quantity, String unit) {}

  /** 一次库存拉取的结果。 */
  record StockSnapshot(List<StockLine> lines, Instant observedAt, String source) {}

  /**
   * 拉取某个仓账户的库存观测。
   *
   * @throws WarehouseProviderException 服务商侧不可用或返回无法解析的载荷
   */
  StockSnapshot fetchStock(ConnectionSettings settings);

  /** 服务商调用失败。区分「未授权」与「暂时不可用」：前者要人工处理，后者可重试。 */
  class WarehouseProviderException extends RuntimeException {
    private final boolean authorizationFailure;

    public WarehouseProviderException(String message, boolean authorizationFailure) {
      super(message);
      this.authorizationFailure = authorizationFailure;
    }

    /** 授权失效：连接状态该转 EXPIRED/EXPIRING，而不是当成偶发网络错误重试。 */
    public boolean authorizationFailure() {
      return authorizationFailure;
    }
  }
}
