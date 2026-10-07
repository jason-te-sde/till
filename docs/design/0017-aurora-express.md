# 17. Aurora through express configuration, signed in with IAM

**Status:** accepted

## Context

`--database=aurora` creates Aurora PostgreSQL Serverless v2 in the VPC with Terraform
(`aws_rds_cluster`, up to 4 ACU), and the account's free plan refused it: FreeTierRestrictionError.
The plan allows Aurora only as **express configuration**, which is a different thing to connect to:

- **No VPC.** The cluster is reached through an *internet access gateway*, a managed endpoint that
  speaks the PostgreSQL wire protocol over TLS, from anywhere.
- **IAM tokens, and nothing else.** A login's password is a token signed for the host, the port and
  the user, valid fifteen minutes; there is no password to set, and none to steal.
- **Created by the CLI** (`create-db-cluster --with-express-configuration`); the Terraform provider has
  no argument for it.
- **Limits:** 4 ACU and 1 GB of storage a cluster, and two database instances in the account, RDS's
  included — a cluster could not be created while the load test's two RDS instances existed
  (InstanceQuotaExceeded).
- The default engine is Aurora PostgreSQL 17.9, so `pg_logical_emit_message(..., flush)` exists for
  the publisher ([ADR 15](0015-async-commit-for-holds.md)); `synchronous_commit = off` is honoured, the
  commit then not waiting for the storage quorum.

The deploy identity may create, modify and delete clusters, enable the gateway and `rds-db:connect`
(`iam simulate-principal-policy`).

## Decision

**`scripts/aws.sh up --database=aurora-express --database-per-service` runs each service on an
Aurora cluster of its own, created with express configuration, and every connection signs in with an
IAM token.**

- **The script owns the clusters.** Before Terraform runs it creates `till-express` and
  `till-store-express` if they are missing, waits for each and its one instance, sets the capacity —
  up to 4 ACU, held at 4 for a load test so that a run does not measure Serverless v2 scaling up
  partway through, and free to pause at 0 otherwise — and turns on Database Insights in its standard
  mode. It hands Terraform each writer endpoint and cluster resource id (`express_clusters`). `down`
  deletes both, instances first, after the running half. The names are apart from everything
  Terraform manages, so that deleting them can take nothing else.
- **One cluster a service**, because the plan allows two instances, and because the services'
  migrations must not share a database: each takes its cluster's default database, `postgres`.
- **The services sign their own tokens.** `till-rds-iam` is pgjdbc's `AuthenticationPlugin`: for every
  connection the pool opens, a token signed with the task role's credentials by the AWS SDK's
  `RdsUtilities` — locally; nothing is called. Both services take it at runtime, and it is loaded only
  when a URL names it: `authenticationPluginClassName=io.till.rds.IamAuthentication`, with
  `sslmode=verify-full` and `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`, so the gateway's
  certificate is checked against the JVM's own roots. The task role may `rds-db:connect` as `postgres`
  to those two clusters and nothing else.
- **What has no IAM of its own gets a token from the script.** The load test's `dbstat` task runs
  `psql`; the script signs a token for the host it asks and hands it in as that run's `PGPASSWORD`.
  The store's create-database step is not needed: its database is the cluster's own.

## Consequences

**A connection costs a signature, not a secret.** No database password exists for these clusters, in
Parameter Store or anywhere else; a leaked token is good for fifteen minutes, for one user, on one
cluster.

**The database is on the internet.** Behind TLS and IAM, but reached through the gateway rather than
inside the VPC, so every round trip leaves the VPC and comes back. With commands batched
([ADR 16](0016-batched-commands.md)) that is two round trips a batch rather than two a command.

**Two instances in all.** A deployment on express Aurora has no RDS instance beside it, and the load
test's two clusters are the account's whole allowance.

**1 GB a cluster.** A load test leaves about a hundred thousand reservations, a quarter of a million
idempotency records and the outbox's events; the clusters are deleted with every `down`, so the limit
is a run's, not a deployment's lifetime's.

## Alternatives

**Upgrading the plan**, for Aurora in the VPC with passwords as `--database=aurora` already does. Not
on this account: it is the free plan, on purpose.

**RDS Proxy** in front of the gateway, for IAM there and passwords behind it. Not supported for a
cluster outside a VPC.

**A long-lived database password on an express cluster.** Not possible: IAM is its only
authentication.
