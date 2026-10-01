# 10. Three brokers, so the event stream survives losing one

**Status:** accepted

## Context

Kafka has always been one broker here — a single KRaft node, broker and controller together
(`docker-compose.yml`, `infra/runtime/services.tf`). Every partition therefore has exactly one
replica, so the ledger's producer asking for `acks=all` ([`KafkaProducers`](../../till-kafka/src/main/java/io/till/kafka/KafkaProducers.java))
was asking the one copy that exists to confirm it has the record — a real guarantee against a
dropped send, but not one against losing the broker itself. A Fargate task is replaced for
ordinary reasons (a deployment, an OOM kill, a host AWS retires), and on that one broker a
replacement comes back with an empty disk: whatever had not reached the store's consumer yet is
gone, because there was never a second copy of it anywhere.

The ledger's outbox ([ADR 6](0006-outbox.md)) already carries every event until it is marked
published, so this was never a risk of *losing an event the ledger ever knew about* — a broker
restarting empty gets the whole unpublished tail again. It was a risk of **the one copy that
exists disappearing between being published and being consumed**: the publisher is told the send
succeeded, marks the row published, and the broker that held it is gone a moment later. Both
`till.kafka.partitions` and `till.kafka.replication-factor` were already configuration
(`TillProperties.Kafka`, `application.yml`) — the ledger's producer and its topic declaration
(`KafkaTopic`) were built to take a replication factor greater than one from the day they were
written, and `docs/operations.md`'s tuning table already described what three would mean. Nothing
was actually running with more than one.

## Decision

Three Kafka nodes, each broker and controller together, replication factor 3 on every topic this
cluster creates for itself or the ledger declares, `min.insync.replicas` 2. A write counts as
delivered once two of the three brokers have it; losing any one of them loses neither the
acknowledged copy nor the ability to take the next write.

**Each broker is its own ECS service** (`kafka-1`, `kafka-2`, `kafka-3` in
`infra/runtime/services.tf`; `kafka`, `kafka-2`, `kafka-3` in the compose override below), not
three tasks of one service, because a KRaft node needs a *stable* identity: its own node id, and
a Cloud Map name (or, locally, a Compose service name) that keeps meaning "this node" across a
replacement. `KAFKA_CONTROLLER_QUORUM_VOTERS` names all three nodes by that stable address,
including the one being configured — unlike today's single node, which could simply dial itself
at `localhost`, a voter in a three-node quorum reaches the *other* two over the network.

**One `CLUSTER_ID`, generated once and given to all three explicitly** (a Terraform `random_id`
resource on AWS; a literal in the compose override, the same way the rest of that file's secrets
are literals). KRaft refuses to let a node join a quorum under a different cluster id than the one
already running, and each node formats its (empty, ephemeral) storage with whichever id it is
given on first boot. Left unset, every node falls back to a value baked into the `apache/kafka`
image — the *same* value on all three, so it would even happen to work, which is exactly why it is
set explicitly instead: a default nobody read is luck, not a decision, and the next base image is
not promised to keep it.

**Security groups admit the brokers to each other**, on 9092 (data replication) and 9093 (the
controller quorum). Being in the same security group tier does not imply that; it needed the same
kind of explicit ingress rule as any other pair of tiers.

### Fargate's storage is ephemeral, and that is accepted, not engineered around

A replaced broker's disk is gone, for both the partition data it held and its share of the
controller quorum's metadata log (the `__cluster_metadata` topic KRaft keeps for itself). Two
different mechanisms, same shape:

- **Partition data.** The empty node rejoins out of the in-sync set and re-replicates from the two
  that still have it. `min.insync.replicas=2` keeps acknowledging writes the entire time, because
  the other two are enough.
- **The controller quorum.** One level down, the same argument: the rejoining node's metadata log
  is behind, and it catches up from the current Raft leader as long as a *majority* of the three —
  two — stay reachable. A deployment that replaces one node at a time
  (`deployment_minimum_healthy_percent = 0`, `deployment_maximum_percent = 100`, unchanged from the
  single-broker setup) never puts two of three controllers down for this reason by itself.

Only two of the three losing their disks **at once** would stall either mechanism — the data
side because there would be nothing left meeting `min.insync.replicas`, the quorum side because
two live votes cannot be assembled from one. That is accepted here rather than solved with
persistent, replicated storage (EFS behind each broker, or MSK) because this deployment is a
session, not a fixture (`infra/README.md`): the worst case of the correlated loss above is a stale
`available` count until the stack is restarted, and the ledger stays authoritative throughout —
nothing downstream can oversell no matter what the storefront's projection currently shows
(`docker-compose.yml`'s comment at the top says why). A session that is going to be torn down in a
few hours does not need Kafka's own storage to outlive the session.

**Three brokers protect against losing one task. They do not protect against losing the zone all
three run in**, which is the same zone the database and the cache are in
(`infra/README.md`, "One zone for everything with state") — a cost trade-off made on purpose for a
demonstration deployment, not an oversight introduced by this change.

### What this does not touch

The ledger's producer (`KafkaProducers`: `acks=all`, idempotent) and its topic declaration
(`KafkaTopic`, `KafkaConfiguration`) needed no code change — `till.kafka.replication-factor` has
been configuration since the outbox first published to Kafka. Only the deployment had never set it
above one. The same is true of `docs/operations.md`'s tuning row for it, written in anticipation of
this.

## Proving it

A configuration nobody runs is a sentence, not a guarantee, so the replicated setup is exercised in
two places:

- **Locally**, [`docker-compose.kafka-cluster.yml`](../../docker-compose.kafka-cluster.yml) adds
  `kafka-2` and `kafka-3` beside `docker-compose.yml`'s `kafka` and gives all three the quorum,
  cluster id and replication settings above; `docker-compose.yml`'s own single broker is
  unchanged, and is still what every other job and a laptop running the whole stack uses, because
  three Kafka JVMs are most of a gigabyte more than one for a property nothing else here needs
  exercised every time.
- **In CI**, the job "the Kafka cluster survives losing a broker" (`.github/workflows/ci.yml`)
  brings up the ledger on that three-broker override and:
  1. declares `till.events` through the ledger's real publisher and confirms the broker's own
     `kafka-topics.sh --describe` reports `ReplicationFactor: 3` and `min.insync.replicas=2`;
  2. stops one broker and confirms the next event the ledger publishes still reaches the topic —
     read back by total offset, not through the storefront, so this job's only concern is the
     broker tier and not the projection `container`'s job already covers;
  3. stops a second broker and confirms the opposite: the ledger's own write still succeeds (it
     only touches its database), but `till_outbox_failures_total` rises and the topic's offset does
     **not** move — a send refused, not one silently accepted and lost;
  4. brings both back and confirms the backlog drains to zero, which is the re-replication
     argument above actually happening rather than only being asserted.

This runs against the compose override with a short script rather than a multi-broker
Testcontainers cluster. Both were considered; the compose route was chosen because this repository
already needed the compose override for the reason above, and a Testcontainers Kafka cluster needs
a second, host-reachable listener per broker with a host port fixed before the container starts
(the three-way split between the controller listener, the inter-broker listener and one a test JVM
outside the Docker network can reach) — real complexity for the same evidence the compose job
already gets for free by exec-ing into a container that is on the right network already. If this
job turns out slow or flaky in practice, revisit that trade-off; today it runs in under five
minutes.

## Consequences

**Kafka's share of the hourly cost triples**, because nothing is shared between the three brokers —
see `infra/README.md`'s arithmetic. At the default size that is $0.0233 an hour times three instead
of once; at the load test's size, $0.0466 times three. Both tables there, and `scripts/aws.sh`'s
own estimate, are updated to the new totals.

**`scripts/aws.sh status`, the safety net's schedules, and the `services` output now name three
services** (`kafka-1`, `kafka-2`, `kafka-3`) instead of one. Anything that used to assume a single
`kafka` ECS service name needed to change; `scripts/aws.sh`'s `kafka_version` and the image it
pushes did not, because all three run the same image.

**A broker that falls behind is now a real possibility**, where before there was nothing to fall
behind *from*. `docs/operations.md` gets a symptom row for it.

## Alternatives

**MSK, or MSK Serverless.** Rejected for the same reason the single broker was
(`infra/README.md`, "Kafka is three brokers on Fargate, not MSK"): MSK Serverless is $0.75 an hour
on its own — five times this entire deployment before this change — and provisioned MSK is two
brokers at the least, before storage. Right for a deployment that outlives a session; not this one.

**Persistent storage under each broker (EFS).** Would remove even the correlated-loss caveat
above. Rejected for now: it needs a mount target per zone, an access point per broker, and a
decision about what "stopped" means for storage that is supposed to outlive the task when
everything else in this deployment is designed to be torn down between sessions
(`infra/README.md`, "What is kept across a stop"). Worth doing if this ever becomes a deployment
that is not stopped between sessions; adding it now would also touch `infra/network.tf` and
`infra/roles.tf`, outside this change's otherwise Kafka-only footprint, at a time when two other
engineers are editing `infra/` for unrelated reasons.

**A Testcontainers cluster instead of the compose override.** Discussed under "Proving it" above.

**Leaving `min.insync.replicas` at 1 with `replication-factor` 3.** Would survive losing a broker
exactly as this does, but would keep acknowledging writes with only one copy durable once a second
broker went down, rather than refusing — reproducing, one broker later, the exact risk this change
exists to close. Rejected.
