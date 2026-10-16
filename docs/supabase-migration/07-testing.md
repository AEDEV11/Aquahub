# Migration testing plan

Status: `PLANNED`.

Required before each module cutover: local Supabase migration apply/rollback checks, RLS allow/deny tests, repository tests, Firebase-vs-Supabase count and aggregate comparisons, and relevant AquaHub/AquaHubCustomer Gradle and Functions/rules tests. Security tests must prove Owner A cannot read or mutate Owner B, station assignments are enforced, customers cannot insert station orders, and server-only records are not exposed.

