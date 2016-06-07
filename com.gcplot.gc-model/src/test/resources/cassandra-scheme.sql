CREATE KEYSPACE gcplot
  WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };

USE gcplot;

CREATE TABLE IF NOT EXISTS gc_analyse (
  id uuid,
  account_id varchar,
  analyse_name varchar,
  is_continuous boolean,
  start timestamp,
  last_event timestamp,
  gc_type int,
  jvm_ids set<varchar>,
  jvm_headers map<varchar, varchar>,
  jvm_md_page_size map<varchar, bigint>,
  jvm_md_phys_total map<varchar, bigint>,
  jvm_md_phys_free map<varchar, bigint>,
  jvm_md_swap_total map<varchar, bigint>,
  jvm_md_swap_free map<varchar, bigint>,
  PRIMARY KEY (account_id, id)
);

CREATE INDEX analyse_ids ON gc_analyse( id );

CREATE TABLE IF NOT EXISTS gc_event (
  id uuid,
  parent_id uuid,
  analyse_id uuid,
  date varchar,
  jvm_id varchar,
  description varchar,
  occurred timeuuid,
  vm_event_type int,
  capacity list<bigint>,
  total_capacity list<bigint>,
  pause_mu bigint,
  duration_mu bigint,
  generations bigint,
  concurrency int,
  ext varchar,
  PRIMARY KEY ((analyse_id, jvm_id, date), occurred)
) WITH CLUSTERING ORDER BY (occurred DESC);

CREATE INDEX gc_generations ON gc_event( generations );
CREATE INDEX gc_event_ids ON gc_event( id );