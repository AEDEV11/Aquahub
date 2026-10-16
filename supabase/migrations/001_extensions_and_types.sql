create extension if not exists pgcrypto;

create type public.order_status as enum (
  'PENDING', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'RIDER_ASSIGNED',
  'OUT_FOR_DELIVERY', 'DELIVERED', 'COMPLETED', 'REJECTED', 'CANCELLED'
);

create type public.member_role as enum ('OWNER', 'MANAGER', 'STAFF', 'RIDER', 'CUSTOMER');
create type public.delivery_mode as enum ('DELIVERY', 'PICKUP');
create type public.subscription_status as enum ('FREE', 'TRIALING', 'ACTIVE', 'ACTIVE_UNTIL_PERIOD_END', 'EXPIRED', 'CANCELLED');

