package com.starcon.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;

/** A trusted tenant, bound only for the lifetime of this database transaction. */
public final class TenantTransactions {
  private final DSLContext sql;
  private final TransactionTemplate transactions;

  public TenantTransactions(DSLContext sql, TransactionTemplate transactions) {
    this.sql = sql;
    this.transactions = transactions;
  }

  public <T> T run(UUID tenant, Supplier<T> work) {
    if (tenant == null) throw new IllegalArgumentException("Missing trusted tenant");
    return transactions.execute(
        status -> {
          sql.fetch("select set_config('app.current_tenant', ?, true)", tenant.toString());
          return work.get();
        });
  }
}
