<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Roadmap

Wanted, not built. Nothing here is in the code.

## Exposure of a table in another format (`lake-convert`)

Let an outside engine read a Delta table as Iceberg, or the reverse, without a copy, using Apache XTable (metadata translation only).
Not on any write path: every store keeps writing through delta-kernel or iceberg-core, and parity stays a property of our own stores.

- Own process (`lake expose --as iceberg <table>`), behind a small `Exposure` SPI; one exposure writer per table, serialised by the product.
- XTable carries no deletion vectors or merge-on-read (tables with deletion vectors are refused by name), and a sync is a second,
  non-atomic commit to the target's metadata; its concurrency behaviour and its Hadoop weight on a machine that takes no `.exe` are unmeasured.
- Before it ships: a two-day spike on XTable's Delta Kernel path (no Spark) on Linux and Windows, and parity-kit cases (the other format's
  official reader returns exactly the source's rows after load, delete-dates, undo and maintenance).
- Moving a table stays the copy conversion (Delta to Iceberg through the Iceberg loader); exposure would be for showing one.

## More storage back-ends

Azure and GCS as `StorageProvider`s (the Hadoop engine is not in the product, so `abfs://`, `gs://` and HDFS arrive only this way).

## A batch form of `VectorSink`

`VectorSink.rows(String[] ids, double[] flat, int[] offsets, int count, String[][] texts)`, only if the benchmark shows the per-row
sink call costs more than 3%; it would replace the row form, not sit beside it.

## Open items of the reactor

What the code still owes its first host is in [java/SPLIT_EXEMPTIONS.md](../java/SPLIT_EXEMPTIONS.md) (the guards read it).
