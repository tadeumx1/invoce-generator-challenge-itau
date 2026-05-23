# Kafka — Zookeeper vs KRaft

Why this project uses Kafka in KRaft mode (no Zookeeper), what the difference actually is,
and when each mode would be the right call.

> TL;DR: **Zookeeper** is a separate cluster that holds Kafka's metadata. **KRaft** (Kafka
> Raft) eliminates the Zookeeper dependency by letting the Kafka brokers themselves hold
> that metadata via a Raft consensus quorum.

---

## Architecture

### With Zookeeper (the legacy model)

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   ZK node 1 │◀──▶│   ZK node 2 │◀──▶│   ZK node 3 │   ← Zookeeper cluster (3+ nodes for quorum)
└──────┬──────┘    └──────┬──────┘    └──────┬──────┘
       │                  │                  │
       │  (metadata: topics, partitions, ACLs, configs)
       ▼                  ▼                  ▼
┌──────────────────────────────────────────────────────┐
│  Kafka Broker 1    Kafka Broker 2    Kafka Broker 3 │   ← separate Kafka cluster
└──────────────────────────────────────────────────────┘
```

- Two physical clusters to maintain — ZK and Kafka.
- ZK elects a **controller** among the Kafka brokers; the controller reads/writes metadata
  to ZK and propagates it to the remaining brokers.
- Every metadata change (create topic, move a partition leader, update an ACL, etc.) round-trips through ZK.

### With KRaft

```
┌─────────────────────────────────────────────────────────┐
│  Kafka Node 1     Kafka Node 2     Kafka Node 3        │
│  (controller +    (controller +    (controller +        │
│   broker)         broker)          broker)              │
└─────────────────────────────────────────────────────────┘
       └─────── Raft quorum (internal metadata) ─────────┘
```

- The Kafka nodes themselves form a Raft quorum (the *controller quorum*) and maintain a
  replicated log of cluster metadata.
- Each node can play `controller`, `broker`, or both (small deployments use combined mode).
- There is no second cluster — Kafka owns its own metadata.

---

## Side-by-side comparison

| Aspect | Zookeeper | KRaft |
| --- | --- | --- |
| **Processes** | 2 clusters (ZK + Kafka) | 1 cluster (Kafka only) |
| **Metadata language** | ZK API (znodes, watches) | Internal topic `__cluster_metadata` (replicated log) |
| **Consensus protocol** | ZAB (ZooKeeper Atomic Broadcast) | Raft |
| **Controller failover** | Re-elected via ZK when the active controller dies (slow) | Near-instant — the Raft quorum always has a hot leader |
| **Practical partition ceiling** | ~200k per cluster (ZK sync becomes the bottleneck) | Millions (Apache has demoed clusters with 2M+ partitions) |
| **Cold start** | Slow — ZK boots first, Kafka registers, controller election | Fast — Raft quorum forms and is immediately ready |
| **Memory / footprint** | ZK JVM + Kafka JVM (2 JVMs per host) | 1 JVM |
| **Configuration** | ~20 ZK properties + Kafka properties | ~10 properties (`KAFKA_PROCESS_ROLES`, `KAFKA_NODE_ID`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, …) |
| **Operations** | Separate ZK backups/upgrades/monitoring/alerts | A single cluster to operate |
| **Multi-tenant ACLs** | Supported | Supported |
| **Transactions** | Supported | Supported |
| **Log compaction** | Supported | Supported |

---

## Performance & scale

The differences that matter in production:

- **Controller failover:** ZK takes ~5–30 s (re-election + reload of metadata) vs KRaft
  ~1–2 s (the quorum already has a leader and the metadata log is replicated).
- **Time to "notice" a broker that came back online:** ZK depends on watches + sync; KRaft
  propagates via Raft commit.
- **Creating 10 000 topics:** an order of magnitude faster on KRaft because each creation
  is an append to the replicated log rather than a sequence of ZK writes.
- **Clusters with 100 k+ partitions:** ZK starts to saturate; KRaft scales comfortably
  beyond it.

---

## Why Zookeeper was the default in the first place

Apache Kafka picked ZK around 2011 because it was the mature distributed-consensus tooling
of its day. It worked well for a decade, but three pain points kept growing:

1. **Two systems to operate** doubles SRE effort (upgrades, backups, alerts,
   troubleshooting).
2. **Scale ceilings** — ZK synchronization became the bottleneck on big clusters
   (> 200 k partitions).
3. **Failover latency** worse than what modern consensus protocols (Raft) can deliver.

The deprecation path:

| Year | Kafka version | Status |
| --- | --- | --- |
| 2019 | — | KIP-500 proposed (Zookeeper removal) |
| 2022 | 3.3 (Oct/2022) | **KRaft marked production-ready** |
| 2023 | 3.5 (Jul/2023) | KRaft becomes the recommended default |
| 2025 | 4.0 (Mar/2025) | **Zookeeper removed entirely** |

---

## Why this project picked KRaft

1. **It is the official path forward.** Apache deprecated Zookeeper in 3.5 and removed it
   in 4.0. The `cp-kafka:7.7` image used in `docker-compose.yml` already runs KRaft natively.
   Standing up Zookeeper in a brand-new project in 2026 means starting from day one with
   tech debt the upstream is not going to support.

2. **Smaller operational footprint for the local demo.** The compose stack is one
   container instead of two; startup is roughly 10–15 s instead of 30–45 s (Zookeeper
   first, then Kafka after the ZK quorum is ready); the healthcheck targets one process
   instead of two.

   | Aspect | KRaft | Zookeeper + Kafka |
   | --- | --- | --- |
   | Containers in compose | **1** | **2** |
   | Boot time | ~10–15 s | ~30–45 s |
   | Healthcheck targets | 1 | 2 |
   | Environment variables | ~10 | ~20 |
   | Log volume directories | 1 | 2 |

3. **AWS production path stays coherent.** Amazon MSK supports KRaft on Kafka 3.7+, and
   **MSK Serverless only supports KRaft**. F-AWS will land on MSK; using KRaft locally
   keeps the dev environment shaped like the production one.

---

## When Zookeeper still makes sense

Honestly, in 2026 the list is short:

- **Existing production cluster on ZK** that you must mirror exactly in dev to preserve
  parity. Match what production runs.
- **Specific Kafka version < 3.3** where KRaft was not yet production-ready.
- **Third-party tooling that still speaks only the ZK API** (rare — most have migrated).

None of those apply to this codebase.

---

## Concrete configuration in this project

The relevant excerpt from [`docker-compose.yml`](../docker-compose.yml):

```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.7.0
    environment:
      KAFKA_NODE_ID: '1'
      KAFKA_PROCESS_ROLES: 'broker,controller'
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@kafka:9093'
      KAFKA_LISTENERS: 'PLAINTEXT://kafka:9092,CONTROLLER://kafka:9093,EXTERNAL://0.0.0.0:29092'
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER'
      # ...
      CLUSTER_ID: '4L6g3nShT-eMCtK--X86sw'  # base64-url-encoded 16-byte UUID
```

Key things to notice:

- `KAFKA_PROCESS_ROLES: broker,controller` puts the broker and the controller quorum role
  on the same JVM. Fine for a single-node demo; production typically separates them.
- `KAFKA_CONTROLLER_QUORUM_VOTERS` lists the controller-quorum members; a single-node demo
  has just one voter.
- `CLUSTER_ID` is a stable 16-byte ID (base64-url-encoded) — required by KRaft so volumes
  can be reused across restarts. Generate one with `kafka-storage random-uuid`.
- No `KAFKA_ZOOKEEPER_CONNECT`. That property does not exist in KRaft mode.

---

## Further reading

- [KIP-500: Replace ZooKeeper with a Self-Managed Metadata Quorum](https://cwiki.apache.org/confluence/display/KAFKA/KIP-500%3A+Replace+ZooKeeper+with+a+Self-Managed+Metadata+Quorum) — the original proposal.
- [Apache Kafka 4.0 release notes](https://kafka.apache.org/blog#apache_kafka_400_release_announcement) — Zookeeper removal milestone.
- [Confluent KRaft documentation](https://docs.confluent.io/platform/current/installation/kraft.html) — operating KRaft on Confluent Platform.
- [Amazon MSK — KRaft support](https://docs.aws.amazon.com/msk/latest/developerguide/kraft-intro.html) — KRaft on Amazon MSK and MSK Serverless.
