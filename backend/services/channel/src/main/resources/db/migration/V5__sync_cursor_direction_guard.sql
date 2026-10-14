-- 2.4.1.3 断点续传：把游标方向约束下沉到 DB。
--
-- 为什么需要这一层：游标是续传的事实来源。一旦被倒退写，下一轮就会从更早的位置
-- 重拉，在平台侧表现为请求放大、在本地表现为幂等去重压力——而且**不会报错、不会留痕**。
-- 只靠"调用方记得别写错"是不够的：completeRun / rewindCursor 之外的任何一条
-- UPDATE 路径（修数据的脚本、后来新增的端点、一次误改）都能安静地推着游标往回跑。
--
-- 因此这里加触发器：任何让 cursor_value 变小的写入，必须来自显式的 rewindCursor 端点。
-- 与 stamp_shop_owner 同一纪律——改事实来源必须走可审计的动作。
-- rewindCursor 端点通过 set_config('app.cursor_rollback', ...) 显式声明意图，
-- 触发器据此放行，于是"谁在什么时候把游标退回到了哪里"只可能来自那个可审计的端点。
--
-- 注意：AccessService.run 把整个端点调用包在单个事务里，
-- set_config(..., true) 是事务内局部设置，因此声明与随后的 UPDATE 落在同一事务，
-- 事务结束即失效，不会泄漏给后续请求。

-- 游标值按下列规则定序：
--   1) 两边都是纯数字时按数值比较——否则 "10" 会被判成早于 "9"，
--      一次合法的推进会被误拦成倒退，把正常同步写死；
--   2) 其余情况按文本比较——ISO-8601 时间戳（游标最常见的形态）
--      与常规字典序单调的分页令牌都满足文本序即时间序。
-- 契约要求：产出游标的一方必须使用字典序单调的令牌。
CREATE FUNCTION cursor_regresses(old_value text, new_value text) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN
  IF old_value ~ '^[0-9]+$' AND new_value ~ '^[0-9]+$' THEN
    RETURN new_value::numeric < old_value::numeric;
  END IF;
  RETURN new_value < old_value;
END $$;

CREATE FUNCTION guard_cursor_direction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.cursor_value IS DISTINCT FROM OLD.cursor_value
     AND cursor_regresses(OLD.cursor_value, NEW.cursor_value)
     AND coalesce(nullif(current_setting('app.cursor_rollback',true),'')::boolean,false) IS NOT TRUE
  THEN
    RAISE EXCEPTION 'Cursor regression must use the explicit rewind endpoint' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER cursor_direction BEFORE UPDATE ON sync_cursor
  FOR EACH ROW EXECUTE FUNCTION guard_cursor_direction();