-- 约束冒烟测试：证明蓝图铁律在 DB 层真的生效（每次改 schema 必跑）
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('app.current_tenant','11111111-1111-1111-1111-111111111111',true);

INSERT INTO stock_owner (id,tenant_id,code,name,owner_type,legal_entity_id)
 VALUES ('aaaaaaaa-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','SELF','自有货','SELF','22222222-0000-0000-0000-000000000002');
INSERT INTO warehouse (id,tenant_id,code,name,warehouse_type,legal_entity_id,country_code,timezone)
 VALUES ('bbbbbbbb-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','WH1','主仓','SELF','22222222-0000-0000-0000-000000000002','CN','Asia/Shanghai');
INSERT INTO stock_bucket (id,tenant_id,stock_owner_id,legal_entity_id,sku_id,warehouse_id,on_hand,reserved)
 VALUES ('cccccccc-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
         'aaaaaaaa-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000002',
         'dddddddd-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000001',10,3);

DO $$
DECLARE t text; ok boolean;
BEGIN
  -- 铁律 1：available 由生成列算出
  SELECT (available = 7) INTO ok FROM stock_bucket WHERE id='cccccccc-0000-0000-0000-000000000001';
  ASSERT ok, 'FAIL: available 必须 = on_hand - reserved';
  RAISE NOTICE 'PASS 铁律1 available 生成列 = 7';

  -- 铁律 2：负库存被拒
  BEGIN UPDATE stock_bucket SET on_hand = -1 WHERE id='cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'FAIL 负库存未被拒'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS 铁律2 拒绝负库存'; END;
  -- 铁律 2：预留超过在手被拒
  BEGIN UPDATE stock_bucket SET reserved = 999 WHERE id='cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'FAIL 预留超在手未被拒'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS 铁律2 拒绝 reserved > on_hand'; END;
  -- 铁律 3：不可售质量状态不得被预留
  BEGIN UPDATE stock_bucket SET quality_status='PENDING_QC' WHERE id='cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'FAIL 待检品被允许预留'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS 铁律3 拒绝不可售状态预留'; END;
END $$;

-- 铁律 4：预占分配终态不可复活
INSERT INTO inventory_reservation (id,tenant_id,reservation_no,source_service,source_type,legal_entity_id,total_qty)
 VALUES ('eeeeeeee-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','R1','trade','sales_order','22222222-0000-0000-0000-000000000002',5);
INSERT INTO reservation_allocation (id,tenant_id,reservation_id,bucket_id,qty,status)
 VALUES ('ffffffff-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
         'eeeeeeee-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000001',5,'CONSUMED');
DO $$ BEGIN
  BEGIN UPDATE reservation_allocation SET status='HELD' WHERE id='ffffffff-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'FAIL 终态预占被复活';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS 铁律4 拒绝终态复活'; END;
END $$;

-- 铁律 5：流水 append-only
INSERT INTO inventory_transaction (id,tenant_id,txn_no,txn_type,source_service,business_date)
 VALUES ('12121212-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','T1','SALE_OUT','trade',current_date);
INSERT INTO inventory_entry (id,tenant_id,txn_id,bucket_id,qty,on_hand_delta)
 VALUES ('13131313-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
         '12121212-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000001',-1,-1);
DO $$ BEGIN
  BEGIN UPDATE inventory_entry SET qty = -2 WHERE id='13131313-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'FAIL 流水被改写';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS 铁律5 流水不可改写'; END;
END $$;

-- 铁律 6：内部转移单 bucket 净额必须为零（延迟约束）
INSERT INTO inventory_transaction (id,tenant_id,txn_no,txn_type,balanced,source_service,business_date)
 VALUES ('14141414-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','T2','TRANSFER_OUT',true,'manual',current_date);
INSERT INTO inventory_entry (id,tenant_id,txn_id,bucket_id,qty,on_hand_delta)
 VALUES ('15151515-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
         '14141414-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000001',2,2);
DO $$ BEGIN
  BEGIN
    EXECUTE 'SET CONSTRAINTS entry_balance_check IMMEDIATE';
    RAISE EXCEPTION 'FAIL 内部转移未净额为零却被接受';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS 铁律6 拒绝 bucket 净额不为零'; END;
END $$;

-- 反例验证：补一条 -2 的反向分录后，净额归零，约束放行
INSERT INTO inventory_entry (id,tenant_id,txn_id,bucket_id,qty,on_hand_delta)
 VALUES ('16161616-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
         '14141414-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000001',-2,-2);
DO $$ BEGIN
  EXECUTE 'SET CONSTRAINTS entry_balance_check IMMEDIATE';
  RAISE NOTICE 'PASS 铁律6b 净额归零后放行（对账可闭合）';
END $$;

-- 跨租户隔离：必须以**运行账号**身份测试。
-- starcon_migrator 是超级用户，超级用户绕过 RLS（即使 FORCE），用它测会假阳性。
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='warehouse_test') THEN
    CREATE ROLE warehouse_test NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;
GRANT USAGE ON SCHEMA public TO warehouse_test;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO warehouse_test;

DO $$
DECLARE n int; BEGIN
  SET LOCAL ROLE warehouse_test;
  SET LOCAL app.current_tenant = '99999999-9999-9999-9999-999999999999';
  SELECT count(*) INTO n FROM stock_bucket;
  ASSERT n = 0, 'FAIL: 换租户后仍读到 ' || n || ' 行（RLS 未隔离）';
  RAISE NOTICE 'PASS RLS 他租户读取返回 0 行';
  SET LOCAL app.current_tenant = '11111111-1111-1111-1111-111111111111';
  SELECT count(*) INTO n FROM stock_bucket;
  ASSERT n = 1, 'FAIL: 本租户应读到 1 行，实际 ' || n;
  RAISE NOTICE 'PASS RLS 本租户读取返回 1 行';
  RESET ROLE;
END $$;

ROLLBACK;
