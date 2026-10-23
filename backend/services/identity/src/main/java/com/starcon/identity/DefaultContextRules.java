package com.starcon.identity;

import static com.starcon.identity.DefaultContextModels.DefaultContextInput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.4.2.2 默认店铺与仓库 —— 载荷形状的**纯函数**校验。
 *
 * <p>刻意做成不碰数据库、不碰网络的纯函数：哪些键被拒、哪些值被拒可以逐条钉住，
 * 而不是只能靠集成测试间接观察。
 *
 * <p><b>本类只管「形状」，不管「在不在权限范围内」</b>：
 *
 * <ul>
 *   <li><b>形状</b>（本类负责）：三个维度各至多一个 UUID；空值就是「不设」；
 *       载荷里不许出现第四个键。</li>
 *   <li><b>在不在权限范围内</b>（服务层负责，必须查上游）：候选集来自 channel /
 *       warehouse 的授权只读端点，「这个 id 在不在你的可见集里」只有那次查询能回答。
 *       本类**不**猜 —— 猜出来的「校验」是假校验。</li>
 * </ul>
 *
 * <p><b>为什么载荷存成 UUID 字符串而不是嵌套对象</b>：个人偏好载荷的形状由
 * {@link UserPreferencePayloadRules}（1.4.1.2）统一把关，它允许一层嵌套，
 * 但三个平铺的标量键更简单、也让「缺哪个键 = 哪个维度没设」这件事一眼可见。
 */
public final class DefaultContextRules {

  private DefaultContextRules() {}

  /** 本模块在个人偏好表里的页面 key（与 1.4.1.1 的 dashboard.layout、1.4.2.1 各自一个）。 */
  public static final String PAGE_KEY = "default-context";

  /** 三个维度的载荷键。 */
  public static final String KEY_SHOP = "shopId";

  public static final String KEY_LEGAL_ENTITY = "legalEntityId";
  public static final String KEY_WAREHOUSE = "warehouseId";

  /** 存下来的三个维度（可能全为 null = 没设过任何默认项）。 */
  public record Stored(UUID shopId, UUID legalEntityId, UUID warehouseId) {

    /** 三个维度都没设。 */
    public boolean empty() {
      return shopId == null && legalEntityId == null && warehouseId == null;
    }
  }

  /**
   * 校验入参并生成要落库的载荷。
   *
   * <p><b>三个键永远都落</b>，没设的维度落一个显式的 null。这样「用户明确清掉了仓库」
   * 与「用户从来没设过仓库」在库里就是两回事：前者不该再被租户默认仓回落覆盖，
   * 后者应该回落。清空与未设的区别只能靠「键在不在 / 值是不是 null」表达。
   *
   * @throws ResponseStatusException 载荷形状不合法时（400，带具体原因）
   */
  public static Map<String, Object> payload(DefaultContextInput input) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认上下文不能为空");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(KEY_SHOP, input.shopId() == null ? null : input.shopId().toString());
    payload.put(KEY_LEGAL_ENTITY, input.legalEntityId() == null ? null : input.legalEntityId().toString());
    payload.put(KEY_WAREHOUSE, input.warehouseId() == null ? null : input.warehouseId().toString());
    return payload;
  }

  /**
   * 把存下来的载荷读回三个维度。
   *
   * <p><b>脏值当没设</b>：老偏好、别的模块写歪了、值不是 UUID —— 一律当「这个维度没设」，
   * 不报错。个人偏好读不出来不该让整个页面崩。
   */
  public static Stored stored(Map<String, Object> payload) {
    if (payload == null) return new Stored(null, null, null);
    return new Stored(
        uuid(payload.get(KEY_SHOP)), uuid(payload.get(KEY_LEGAL_ENTITY)), uuid(payload.get(KEY_WAREHOUSE)));
  }

  private static UUID uuid(Object value) {
    if (value == null) return null;
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
