-- ============================================
-- DataFlow Platform - Cassandra Initialization
-- ============================================
-- Creates keyspaces and tables for Pekko Persistence

-- ============================================
-- JOURNAL KEYSPACE (Event Store)
-- ============================================

CREATE KEYSPACE IF NOT EXISTS dataflow_journal
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

USE dataflow_journal;

-- Messages table (event journal)
CREATE TABLE IF NOT EXISTS messages (
  persistence_id text,
  partition_nr bigint,
  sequence_nr bigint,
  timestamp timeuuid,
  timebucket text,
  writer_uuid text,
  ser_id int,
  ser_manifest text,
  event_manifest text,
  event blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  tags set<text>,
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH gc_grace_seconds = 864000
  AND compaction = {
    'class' : 'SizeTieredCompactionStrategy',
    'enabled' : true
  };

-- Tag views table (for event tags)
CREATE TABLE IF NOT EXISTS tag_views (
  tag_name text,
  persistence_id text,
  sequence_nr bigint,
  timebucket bigint,
  timestamp timeuuid,
  tag_pid_sequence_nr bigint,
  writer_uuid text,
  ser_id int,
  ser_manifest text,
  event_manifest text,
  event blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY ((tag_name, timebucket), timestamp, persistence_id, tag_pid_sequence_nr)
) WITH gc_grace_seconds = 864000
  AND compaction = {
    'class' : 'SizeTieredCompactionStrategy',
    'enabled' : true
  };

-- Tag write progress table
CREATE TABLE IF NOT EXISTS tag_write_progress (
  persistence_id text,
  tag text,
  sequence_nr bigint,
  tag_pid_sequence_nr bigint,
  offset timeuuid,
  PRIMARY KEY (persistence_id, tag)
);

-- Tag scanning table
CREATE TABLE IF NOT EXISTS tag_scanning (
  persistence_id text,
  sequence_nr bigint,
  PRIMARY KEY (persistence_id)
);

-- Metadata table
CREATE TABLE IF NOT EXISTS metadata (
  persistence_id text PRIMARY KEY,
  deleted_to bigint,
  properties map<text,text>
);

-- All persistence ids table
CREATE TABLE IF NOT EXISTS all_persistence_ids (
  persistence_id text PRIMARY KEY
);

-- ============================================
-- SNAPSHOT KEYSPACE
-- ============================================

CREATE KEYSPACE IF NOT EXISTS dataflow_snapshot
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

USE dataflow_snapshot;

-- Snapshots table
CREATE TABLE IF NOT EXISTS snapshots (
  persistence_id text,
  sequence_nr bigint,
  timestamp bigint,
  ser_id int,
  ser_manifest text,
  snapshot_data blob,
  snapshot blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY (persistence_id, sequence_nr)
) WITH CLUSTERING ORDER BY (sequence_nr DESC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class' : 'SizeTieredCompactionStrategy',
    'enabled' : true
  };

-- ============================================
-- COMPLETED
-- ============================================

DESCRIBE KEYSPACES;
