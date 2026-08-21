# Storage spike — raw output (2026-08-21T13:09:44Z, 1000000 rows, 200 iterations
 pg_terminate_backend 
----------------------
 t
(1 row)

## Schema
## Seed

## Variant A — pure view + expression indexes on base

## Variant B — generated table, trigger-maintained, STORED generated columns

## Timings (avg/max ms over warm cache)
NOTICE:  RESULT|A|point read|runs=200|avg_ms=0.037|max_ms=6.952
NOTICE:  RESULT|B|point read|runs=200|avg_ms=0.024|max_ms=4.239
NOTICE:  RESULT|A|list p50|runs=200|avg_ms=3.637|max_ms=349.537
NOTICE:  RESULT|B|list p50|runs=200|avg_ms=2.177|max_ms=122.533
NOTICE:  RESULT|A|unique lookup|runs=200|avg_ms=0.021|max_ms=2.923
NOTICE:  RESULT|B|unique lookup|runs=200|avg_ms=0.012|max_ms=1.389
NOTICE:  RESULT|A|insert|runs=200|avg_ms=0.437|max_ms=5.614
NOTICE:  RESULT|B|insert|runs=200|avg_ms=0.242|max_ms=1.701

## Plans — list query
### Variant A
                                                                                    QUERY PLAN                                                                                    
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit (actual time=7.933..7.978 rows=50 loops=1)
   Buffers: shared hit=4259
   ->  Index Scan using ix_a_entry_date on rec_records (actual time=7.932..7.973 rows=50 loops=1)
         Index Cond: ((tenant_id = '11111111-1111-4111-8111-111111111111'::uuid) AND (entity_id = 'JournalEntry'::text) AND ((data ->> 'entryDate'::text) >= '2026-05-01'::text))
         Filter: ((data ->> 'status'::text) = 'POSTED'::text)
         Rows Removed by Filter: 4166
         Buffers: shared hit=4259
 Planning:
   Buffers: shared hit=189 read=3
 Planning Time: 0.460 ms
 Execution Time: 8.020 ms
(11 rows)

### Variant B
                                                       QUERY PLAN                                                        
-------------------------------------------------------------------------------------------------------------------------
 Limit (actual time=7.660..7.687 rows=50 loops=1)
   Buffers: shared hit=4262
   ->  Index Scan using ix_b_entry_date on rec_journal_entry_b (actual time=7.658..7.683 rows=50 loops=1)
         Index Cond: ((tenant_id = '11111111-1111-4111-8111-111111111111'::uuid) AND (entry_date >= '2026-05-01'::text))
         Filter: ((data ->> 'status'::text) = 'POSTED'::text)
         Rows Removed by Filter: 4166
         Buffers: shared hit=4262
 Planning:
   Buffers: shared hit=131
 Planning Time: 0.326 ms
 Execution Time: 7.712 ms
(11 rows)


## Table sizes
       relname       | total_size 
---------------------+------------
 ix_a_entry_date     | 74 MB
 ix_a_reference      | 65 MB
 ix_b_entry_date     | 85 MB
 ix_b_reference      | 47 MB
 rec_journal_entry_b | 450 MB
 rec_records         | 446 MB
(6 rows)

