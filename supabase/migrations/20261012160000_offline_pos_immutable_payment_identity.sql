-- Preserve append-only financial history while giving an offline cash receipt
-- its operation-scoped idempotency key in the same transaction as the order.
-- The order trigger first creates the normal walk-in payment. The offline
-- operation row is then inserted; this trigger binds that receipt to the
-- verified offline operation before the transaction becomes visible.

create or replace function app_private.prevent_immutable_payment_history_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if current_user = 'postgres'
     and tg_table_name = 'payments'
     and tg_op = 'UPDATE'
     and old.status = 'PAID'
     and new.status = old.status
     and old.operation_id = 'payment:' || old.order_id::text || ':walk-in'
     and new.operation_id like 'offline-pos-payment:%'
     and (to_jsonb(new) - 'operation_id' - 'metadata')
         is not distinct from (to_jsonb(old) - 'operation_id' - 'metadata')
     and exists (
       select 1
       from app_private.offline_pos_operations o
       where o.order_id = new.order_id
         and new.order_id = old.order_id
         and new.operation_id = 'offline-pos-payment:' || o.operation_id::text
         and new.metadata ->> 'offline_operation_id' = o.operation_id::text
         and new.metadata ->> 'payload_digest' = o.payload_digest
         and new.metadata ->> 'cash_received_centavos' = o.cash_received_centavos::text
         and new.metadata ->> 'change_centavos' = o.change_centavos::text
     ) then
    return new;
  end if;

  if current_user <> 'postgres' then
    raise exception using errcode = '55000',
      message = 'financial history is immutable; record a compensating event instead';
  end if;
  return old;
end;
$$;

revoke all on function app_private.prevent_immutable_payment_history_mutation()
  from public, anon, authenticated, service_role;
grant execute on function app_private.prevent_immutable_payment_history_mutation() to postgres;

create or replace function app_private.link_offline_cash_payment()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.payments p
  set operation_id = 'offline-pos-payment:' || new.operation_id::text,
      metadata = coalesce(p.metadata, '{}'::jsonb) || jsonb_build_object(
        'offline_operation_id', new.operation_id,
        'cash_received_centavos', new.cash_received_centavos,
        'change_centavos', new.change_centavos,
        'payload_digest', new.payload_digest
      )
  where p.order_id = new.order_id
    and p.status = 'PAID'
    and p.operation_id = 'payment:' || new.order_id::text || ':walk-in';

  if not found and not exists (
    select 1
    from public.payments p
    where p.order_id = new.order_id
      and p.status = 'PAID'
      and p.operation_id = 'offline-pos-payment:' || new.operation_id::text
      and p.metadata ->> 'offline_operation_id' = new.operation_id::text
      and p.metadata ->> 'payload_digest' = new.payload_digest
      and p.metadata ->> 'cash_received_centavos' = new.cash_received_centavos::text
      and p.metadata ->> 'change_centavos' = new.change_centavos::text
  ) then
    raise exception using errcode = '55000',
      message = 'offline cash receipt could not be linked to its immutable operation';
  end if;

  return new;
end;
$$;

revoke all on function app_private.link_offline_cash_payment()
  from public, anon, authenticated, service_role;
grant execute on function app_private.link_offline_cash_payment() to postgres;

drop trigger if exists offline_pos_link_cash_payment on app_private.offline_pos_operations;
create trigger offline_pos_link_cash_payment
after insert on app_private.offline_pos_operations
for each row execute function app_private.link_offline_cash_payment();

comment on function app_private.link_offline_cash_payment() is
  'Links the insert-only walk-in cash receipt to its committed offline operation before transaction commit.';
