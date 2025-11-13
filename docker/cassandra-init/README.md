# Cassandra Initialization for DataFlow Platform

This directory contains scripts to initialize Cassandra keyspaces and tables for Pekko Persistence.

## Quick Start

### Option 1: Automatic Initialization (Recommended)

Run the initialization script:

```bash
cd docker/cassandra-init
./init-cassandra.sh
```

This will:
1. Wait for Cassandra to be ready
2. Create the `dataflow_journal` keyspace
3. Create the `dataflow_snapshot` keyspace
4. Create all required tables

### Option 2: Manual Initialization

If you prefer to run the CQL manually:

```bash
# Using Docker
docker exec -i dataflow-cassandra cqlsh < 01-init-keyspaces.cql

# Or using cqlsh directly
cqlsh -f 01-init-keyspaces.cql
```

## What Gets Created

### dataflow_journal Keyspace
Event store for Pekko Persistence event sourcing:
- `messages` - Event journal
- `tag_views` - Tagged events for projections
- `tag_write_progress` - Tag write tracking
- `tag_scanning` - Tag scanning state
- `metadata` - Persistence metadata
- `all_persistence_ids` - Registry of all persistence IDs

### dataflow_snapshot Keyspace
Snapshot store for Pekko Persistence:
- `snapshots` - Actor state snapshots

## Configuration

The keyspaces use `SimpleStrategy` with replication factor 1 (suitable for development).

For production, you should:
1. Use `NetworkTopologyStrategy` with appropriate replication
2. Adjust compaction settings
3. Configure appropriate gc_grace_seconds

## Troubleshooting

### "Connection refused" error
- Make sure Cassandra is running: `docker ps | grep cassandra`
- Start Cassandra: `cd docker && docker-compose up -d cassandra`

### "Keyspace already exists" warnings
- This is normal if you run the script multiple times
- The script uses `IF NOT EXISTS` clauses

### Keyspace won't create
- Check Cassandra logs: `docker logs dataflow-cassandra`
- Verify connectivity: `docker exec dataflow-cassandra cqlsh -e "SELECT now() FROM system.local"`

## Verification

To verify the setup:

```bash
# List keyspaces
docker exec dataflow-cassandra cqlsh -e "DESCRIBE KEYSPACES"

# Check journal tables
docker exec dataflow-cassandra cqlsh -e "DESCRIBE dataflow_journal"

# Check snapshot tables
docker exec dataflow-cassandra cqlsh -e "DESCRIBE dataflow_snapshot"
```

## Auto-Creation

The application configuration includes auto-creation settings that will create tables if they don't exist. However, keyspaces must be created manually or via this script.

## Production Recommendations

For production deployments:

1. **Create keyspaces manually** with proper replication:
   ```cql
   CREATE KEYSPACE dataflow_journal
   WITH replication = {
     'class': 'NetworkTopologyStrategy',
     'datacenter1': 3
   };
   ```

2. **Tune compaction** for your workload
3. **Set appropriate gc_grace_seconds**
4. **Configure backup/restore procedures**
5. **Monitor disk usage and compaction**

## References

- [Pekko Persistence Cassandra](https://pekko.apache.org/docs/pekko-persistence-cassandra/current/)
- [Cassandra Data Modeling](https://cassandra.apache.org/doc/latest/cassandra/data_modeling/)
