# Collection clustering

`CLUSTER_COLLECTION` and `ANALYZE_COLLECTION_DIVERSITY` jobs create immutable run snapshots. Each run stores collection, embedding model identity, algorithm version, parameters, statistics, centroids, representative original, member distances, and explicit outliers. Re-analysis creates a new run instead of rewriting history.

Algorithm: `DBSCAN-cosine-bounded-v1`, default epsilon 0.16 cosine distance and minimum three points including the point itself. Asset UUIDs determine traversal order. PostgreSQL retrieves at most 200 neighbors per point within the collection. The application performs deterministic density expansion on this bounded graph; it never computes a full library pairwise matrix. This bounded neighborhood approximation can differ from exhaustive DBSCAN in very dense collections.

A run admits at most 5,000 originals and requires complete embeddings for its captured collection membership. The vector snapshot is bounded to this collection. Larger collections must currently be split for analysis; increasing this cap alone is not a substitute for measuring worker memory and query cost. Retrieval across the entire library remains indexed and does not share this clustering limit. Concurrent collection growth can make a completed snapshot stale; compare its asset count with current coverage and rerun.

For each non-noise cluster, the normalized mean embedding is its centroid. The representative is the member with the smallest cosine distance to that centroid (stable UUID tie order). Noise has no centroid or representative. Public APIs return member IDs and scalar distances, never full vectors.

Statistics have explicit meanings:

- `assetCount`: originals in the captured run.
- `clusterCount`: non-noise families.
- `largestClusterShare`: size of the largest non-noise family divided by all captured originals; zero for empty/all-noise runs.
- `meanNearestNeighborSimilarity`: average cosine similarity to the closest other retrieved collection original; null if no neighbor exists.
- `nearDuplicatePairCount`: distinct persisted current-profile pairs classified exact/perceptual/near for this collection and model.
- `outlierCount`: assets labeled noise.
- `coverage`: one for a successfully completed run; incomplete embeddings prevent completion.

No unexplained aggregate diversity score is produced. The UI shows family representatives, member previews, scalar statistics, coverage, and outliers. Clusters are analytical groupings rather than absolute semantic truth. Test fixtures cover two dense groups and an outlier, deterministic replay, 100-asset backfill, and staged-batch saturation. A representative 100,000-asset clustering benchmark has not been run.
