package com.starcon.finance;

import com.starcon.finance.BadDebtModels.BadDebtReasonType;
import com.starcon.finance.BadDebtModels.BadDebtStatus;
import java.util.UUID;

/**
 * 坏账核销申请列表的筛选条件。
 *
 * <p>可选参数全部可空 —— {@code null} = 不筛。
 *
 * @param page 页码（从 0 开始）。
 * @param size 每页条数（服务端钳在 1..200）。
 */
public record BadDebtQuery(
    BadDebtStatus status,
    BadDebtReasonType reasonType,
    String currency,
    UUID receivableId,
    UUID disputeId,
    UUID shopId,
    String keyword,
    int page,
    int size) {}
