-- The helper is an administrative maintenance function, not an API operation.
-- It must never be callable through the exposed PostgREST schema.
do $$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
    grant execute on function public.rls_auto_enable() to postgres, service_role;
  end if;
end
$$;
