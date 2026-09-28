package com.starcon.identity;

import com.starcon.tenancy.Ids;
import java.sql.DriverManager;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Explicit local-only bootstrap. No passwords or fixed business tenant IDs in migrations. */
@Component
@ConditionalOnProperty(name = "LOCAL_BOOTSTRAP_ENABLED", havingValue = "true")
public class LocalBootstrap implements ApplicationRunner {
  private final Environment env;

  public LocalBootstrap(Environment env) {
    this.env = env;
  }

  public void run(ApplicationArguments args) throws Exception {
    try (var connection =
        DriverManager.getConnection(
            env.getRequiredProperty("DB_URL"),
            env.getRequiredProperty("DB_MIGRATION_USER"),
            env.getRequiredProperty("DB_MIGRATION_PASSWORD"))) {
      var sql = DSL.using(connection, SQLDialect.POSTGRES);
      sql.transaction(
          config -> {
            var tx = DSL.using(config);
            if (tx.fetchExists(
                tx.selectFrom(DSL.table("app_user"))
                    .where(DSL.field("username").eq("local-admin")))) return;
            UUID tenant = Ids.next(), user = Ids.next(), member = Ids.next(), role = Ids.next();
            tx.execute("insert into tenant(id,code,name) values(?,?,?)", tenant, "LOCAL", "本地开发租户");
            tx.execute(
                "insert into app_user values(?,?,?,'ACTIVE')",
                user,
                "local-admin",
                new BCryptPasswordEncoder()
                    .encode(env.getRequiredProperty("LOCAL_ADMIN_PASSWORD")));
            tx.execute(
                "insert into membership(id,tenant_id,user_id) values(?,?,?)", member, tenant, user);
            tx.execute("insert into tenant_closure values(?,?,0)", tenant, tenant);
            tx.execute(
                "insert into role(id,tenant_id,code,name) values(?,?,?,'租户所有者')",
                role,
                tenant,
                "OWNER");
            tx.execute("insert into role_permission select ?,?,code from permission", tenant, role);
            tx.execute("insert into role_assignment values(?,?,?,'ALL')", tenant, member, role);
          });
    }
  }
}
