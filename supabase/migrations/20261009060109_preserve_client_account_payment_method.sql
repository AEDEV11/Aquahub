-- The order payment method records how the sale was charged. Receipt methods
-- belong to payment rows and must not erase a Client Account receivable.
create or replace function app_private.keep_client_account_order_method()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  new.payment_method := old.payment_method;
  return new;
end;
$$;

revoke all on function app_private.keep_client_account_order_method()
  from public, anon, authenticated, service_role;
grant execute on function app_private.keep_client_account_order_method()
  to postgres;

drop trigger if exists orders_preserve_client_account_payment_method on public.orders;
create trigger orders_preserve_client_account_payment_method
before update of payment_method on public.orders
for each row
when (old.payment_method = 'CLIENT_ACCOUNT' and new.payment_method is distinct from old.payment_method)
execute function app_private.keep_client_account_order_method();
