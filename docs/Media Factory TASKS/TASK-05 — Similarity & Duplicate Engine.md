# TASK-05 — Similarity & Duplicate Engine

## Objective

Build a production-ready Similarity & Duplicate Engine for Media Factory.

The system must detect:

* exact file duplicates;
* perceptually identical images;
* near-duplicates;
* visually similar images;
* semantically similar images;
* excessive similarity inside collections;
* repetitive generation patterns;
* duplicate candidates across the entire Media Factory library.

Implement:

* SHA-256 exact duplicate detection;
* perceptual hashing (`pHash`);
* image embeddings;
* CLIP-compatible embedding architecture;
* PostgreSQL `pgvector`;
* vector nearest-neighbor search;
* configurable similarity thresholds;
* similarity profiles;
* similarity findings;
* duplicate groups;
* near-duplicate groups;
* collection clustering;
* diversity analysis;
* pre-publication duplicate protection;
* pre-generation diversity protection;
* prompt/concept similarity foundation;
* human duplicate override;
* background embedding jobs;
* embedding model versioning;
* re-indexing support;
* batch processing;
* observability;
* tests;
* frontend similarity explorer.

The primary pipeline should become:

```text
Generated Asset
      ↓
Exact Duplicate Check
      ↓
Perceptual Similarity
      ↓
Embedding Generation
      ↓
Vector Search
      ↓
Similarity Evaluation
      ↓
┌──────────────┬───────────────┬──────────────┐
│ UNIQUE       │ NEAR_DUPLICATE│ DUPLICATE    │
└──────────────┴───────────────┴──────────────┘
                       ↓
               Diversity Policy
                       ↓
               QA / Human Review
```

The engine must also support:

```text
Collection
     ↓
Embedding Space
     ↓
Clustering
     ↓
Visual Families
     ↓
Diversity Analysis
```

and eventually:

```text
New Concept
     ↓
Existing Library
     ↓
Similarity Guard
     ↓
GENERATE / WARN / BLOCK
```

The system must not reduce similarity to one simplistic metric.

---

# 1. Inspect Existing Architecture

Before implementation:

1. inspect TASK-01 Media Factory foundation;
2. inspect TASK-02 provider architecture;
3. inspect TASK-03 Prompt Engine;
4. inspect TASK-04 Advanced Visual QA;
5. inspect existing SHA-256 duplicate detection;
6. inspect `Asset`, `Generation`, `Collection`, `Concept`, `QualityReview`, and job architecture;
7. inspect PostgreSQL/Flyway configuration;
8. verify whether `pgvector` is already enabled;
9. reuse existing async jobs, storage abstractions, retry infrastructure, and metrics conventions.

Do not build a second duplicate-detection subsystem beside existing TASK-01 logic.

Refactor existing duplicate logic into the new Similarity Engine where appropriate.

Do not modify already-applied Flyway migrations.

---

# 2. Core Architectural Principle

Similarity has multiple meanings.

Keep them separate:

```text
EXACT DUPLICATE
SHA-256

PERCEPTUAL DUPLICATE
pHash

VISUAL SIMILARITY
image embeddings

SEMANTIC SIMILARITY
CLIP-compatible embeddings

COLLECTION SIMILARITY
distribution / clusters
```

Do NOT combine all of them immediately into one unexplained:

```text
similarityScore = 0.87
```

Persist individual measurements.

Policies may combine them later.

---

# 3. Target Architecture

Implement:

```text
                    Asset
                      │
            ┌─────────┼─────────┐
            ↓         ↓         ↓
          SHA256     pHash    Embedding
            │         │         │
            └─────────┼─────────┘
                      ↓
              Similarity Engine
                      ↓
              Candidate Search
                      ↓
              Pair Evaluation
                      ↓
              Similarity Policy
                      ↓
        ┌─────────────┼──────────────┐
        ↓             ↓              ↓
      UNIQUE      NEAR_DUPLICATE   DUPLICATE
                      │              │
                      └──────┬───────┘
                             ↓
                         QA Review
```

Keep:

```text
feature extraction
```

separate from:

```text
candidate retrieval
```

and:

```text
policy decision
```

---

# 4. Core Domain Concepts

Introduce/refine:

```text
AssetFingerprint

AssetEmbedding

SimilarityComparison

SimilarityFinding

DuplicateGroup

DuplicateGroupMember

SimilarityProfile

CollectionCluster

CollectionClusterMember
```

Avoid storing everything directly on `Asset`.

---

# 5. Asset Fingerprints

Each image Asset should support fingerprints:

```text
SHA256
PHASH
```

Suggested model:

```text
AssetFingerprint

id
assetId

type
value

algorithm
algorithmVersion

createdAt
```

Fingerprint type:

```text
SHA256
PHASH
```

Architecture should support future types.

---

# 6. SHA-256

SHA-256 remains authoritative for exact byte duplicates.

Flow:

```text
Asset
↓
SHA-256
↓
existing fingerprint lookup
↓
match?
```

Result:

```text
EXACT_DUPLICATE
```

Do not perform expensive embedding search before checking exact duplicates.

Use a database unique/index strategy appropriate to the existing asset lifecycle.

---

# 7. Important Exact-Duplicate Distinction

Two files may represent the same visual content but have different:

```text
compression
metadata
format
resolution
```

Therefore:

```text
SHA-256 mismatch
```

does NOT mean:

```text
unique image
```

This is why pHash and embeddings are required.

---

# 8. Perceptual Hash

Implement perceptual hashing.

Preferred initial algorithm:

```text
pHash
```

Use a reliable maintained implementation or implement the algorithm carefully with tests.

Do not confuse:

```text
aHash
dHash
pHash
```

Document the chosen algorithm.

Persist algorithm version.

---

# 9. pHash Comparison

Use Hamming distance.

Conceptually:

```text
distance = hamming(phashA, phashB)
```

Smaller distance means greater perceptual similarity.

Example policy ranges must be calibrated through tests.

Do NOT blindly hardcode arbitrary internet thresholds as universal truth.

Provide configurable thresholds:

```yaml
similarity:

  phash:

    duplicate-max-distance: 4

    near-duplicate-max-distance: 10
```

These values are initial defaults only and must be documented as tunable.

---

# 10. Embedding Architecture

Create a provider abstraction:

```java
public interface ImageEmbeddingProvider {

    String providerId();

    ImageEmbeddingResult embed(
        ImageEmbeddingRequest request
    );

    EmbeddingModelMetadata modelMetadata();
}
```

Provider must return:

```text
embedding vector

model
model version

dimension

provider metadata
```

Do not expose Python/SDK-specific classes to the Java domain layer.

---

# 11. CLIP-Compatible Embeddings

Implement the first image embedding provider using a CLIP-compatible model suitable for visual/semantic similarity.

The architecture must NOT depend permanently on one specific CLIP implementation.

Possible future models:

```text
CLIP
OpenCLIP
SigLIP
provider-hosted multimodal embedding models
```

Use the actual model selected by the implementation environment.

Document exactly:

```text
model name
model version
embedding dimension
preprocessing
normalization
```

Do not simply write:

```text
CLIP embedding
```

without identifying the model.

---

# 12. Local Embedding Worker

Prefer a local embedding worker when practical.

Suggested architecture:

```text
Spring Boot
     ↓
Embedding Job
     ↓
Python Worker
     ↓
CLIP-compatible model
     ↓
vector
     ↓
Spring / DB
```

Worker location:

```text
workers/
    similarity/
        embedding/
```

The model should load once per worker process rather than once per image.

Support batching.

---

# 13. GPU Acceleration

Embedding worker should detect/use GPU acceleration where available.

Support:

```text
CUDA
CPU fallback
```

Do not make GPU mandatory.

Configuration example:

```yaml
similarity:

  embedding:

    device: AUTO

    batch-size: 32
```

`AUTO` should select an available supported accelerator, otherwise CPU.

Log selected device at startup.

---

# 14. Embedding Model Lifecycle

Model initialization can be expensive.

Do NOT:

```text
load model
embed one image
destroy model
```

for every job.

Use a long-lived worker/service.

Architecture should support:

```text
batch inference
```

for backfills and collection processing.

---

# 15. Asset Embedding Model

Persist:

```text
AssetEmbedding

id

assetId

provider

model
modelVersion

dimension

embedding

normalized

createdAt
```

Use:

```text
pgvector
```

for the vector column.

An Asset may have multiple embeddings from different model versions.

---

# 16. Embedding Identity

Uniqueness should include:

```text
assetId
provider
model
modelVersion
```

Do not overwrite old embeddings when upgrading models.

This allows:

```text
CLIP model A
↓
historical index

new model B
↓
re-index
```

without corrupting historical comparisons.

---

# 17. pgvector

Ensure PostgreSQL has:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

through appropriate Flyway migration.

Use pgvector for nearest-neighbor queries.

Do not pull every vector into Java and calculate similarity across the entire library.

---

# 18. Distance Metric

Choose and document the embedding distance metric.

For normalized CLIP-style embeddings, likely:

```text
cosine similarity
```

or equivalent cosine distance.

Keep naming clear.

Example:

```text
cosineSimilarity = 1 - cosineDistance
```

Do not mix distance and similarity semantics.

Standardize application output to:

```text
0.0 → unrelated

1.0 → highly similar
```

where appropriate.

---

# 19. Vector Index

Create an appropriate pgvector index when dataset size justifies it.

Evaluate:

```text
HNSW
```

versus:

```text
IVFFlat
```

Prefer the simplest robust choice for expected Media Factory scale.

Document:

```text
index type
operator class
distance metric
configuration
```

Do not create an index incompatible with the selected distance function.

---

# 20. Candidate Retrieval

For a new Asset:

```text
embedding
↓
pgvector nearest neighbors
↓
top K candidates
```

Configuration:

```yaml
similarity:

  vector-search:

    top-k: 50

    minimum-similarity: 0.70
```

Do not compare against every asset in application memory.

---

# 21. Multi-Stage Similarity Pipeline

Optimize evaluation order.

Recommended:

```text
1. SHA-256
      ↓
2. pHash
      ↓
3. embedding nearest neighbors
      ↓
4. policy evaluation
```

Potential short-circuit:

```text
SHA256 exact match
→ exact duplicate known immediately
```

but still create enough structured records for auditability.

---

# 22. Similarity Comparison

Persist meaningful comparisons.

Suggested model:

```text
SimilarityComparison

id

sourceAssetId
targetAssetId

sha256Match

phashDistance

embeddingSimilarity

embeddingModel
embeddingModelVersion

classification

createdAt
```

Classification:

```text
EXACT_DUPLICATE

PERCEPTUAL_DUPLICATE

NEAR_DUPLICATE

VISUALLY_SIMILAR

SEMANTICALLY_SIMILAR

DISTINCT
```

Avoid creating database rows for millions of irrelevant low-similarity pairs.

Persist only meaningful candidates above configurable thresholds or those required for audit/history.

---

# 23. Similarity Direction

Asset-pair similarity is conceptually symmetric.

Normalize pair identity.

For example:

```text
min(assetA, assetB)
max(assetA, assetB)
```

or enforce an equivalent canonical pair representation.

Avoid duplicate records:

```text
A → B
B → A
```

for the same comparison.

---

# 24. Similarity Policy

Create:

```java
public interface SimilarityPolicy {

    SimilarityDecision evaluate(
        SimilarityEvaluationContext context
    );
}
```

Do not place duplicate classification logic inside embedding providers.

Example decisions:

```text
UNIQUE

SIMILAR

NEAR_DUPLICATE

DUPLICATE

NEEDS_REVIEW
```

---

# 25. Similarity Profiles

Support profiles.

Examples:

```text
WALLPAPER

STOCK_STRICT

SOCIAL

GENERATION_DIVERSITY
```

Different pipelines have different tolerance.

Example:

```yaml
similarity:

  profiles:

    stock-strict:

      exact-duplicate:
        action: BLOCK

      phash:
        duplicate-max-distance: 4

      embedding:
        near-duplicate-threshold: 0.94

        similar-threshold: 0.86
```

Thresholds must be configurable and calibrated.

---

# 26. Do Not Trust One Metric Alone

A high embedding similarity does not automatically mean duplicate.

Example:

```text
two different wolf portraits
```

may be semantically very similar while visually distinct.

Therefore policy should be able to reason about:

```text
SHA match

pHash distance

embedding similarity

same concept

same collection

same prompt version

generation lineage
```

Do not treat:

```text
embeddingSimilarity > X
```

as universal duplicate proof.

---

# 27. Generation Lineage Awareness

TASK-04 introduced regeneration lineage.

Similarity Engine must understand:

```text
Generation 100
↓
regeneration
↓
Generation 101
```

High similarity between parent and regenerated child may be expected.

Still record it, but policy may treat it differently.

Add context:

```text
sameGenerationFamily
```

or equivalent.

---

# 28. Collection Awareness

Similarity policy must understand:

```text
same collection

different collection
```

Example:

```text
Cyber Wolves #1
Cyber Wolves #2
```

should be related but sufficiently distinct.

Cross-collection duplication may indicate accidental reuse.

Persist context rather than embedding collection logic into feature extraction.

---

# 29. Duplicate Groups

Create groups representing known duplicate families.

```text
DuplicateGroup

id

type

status

canonicalAssetId

createdAt
updatedAt
```

Types:

```text
EXACT

PERCEPTUAL

NEAR_DUPLICATE
```

Members:

```text
DuplicateGroupMember

groupId
assetId

relationship

addedAt
```

---

# 30. Canonical Asset

Duplicate group should support selecting a canonical asset.

Example:

```text
5 visually equivalent files

↓
canonical

highest-resolution approved original
```

Do not automatically delete duplicates.

Canonical selection should be explicit/policy-driven.

---

# 31. Duplicate Handling

Actions may include:

```text
ALLOW

WARN

NEEDS_REVIEW

BLOCK_PUBLICATION

MARK_DUPLICATE
```

Do NOT automatically delete generated media.

Preserve provenance and cost history.

---

# 32. QA Integration

TASK-04 must receive similarity findings.

Example:

```json
{
  "code": "NEAR_DUPLICATE",
  "category": "SIMILARITY",
  "severity": "MAJOR",
  "confidence": 0.96,
  "metadata": {
    "similarAssetId": "...",
    "embeddingSimilarity": 0.97,
    "phashDistance": 5
  }
}
```

QA Policy decides whether this means:

```text
APPROVED

NEEDS_REVIEW

REJECTED
```

Similarity Engine detects.

QA policy decides.

---

# 33. Publication Guard

Publication must check similarity policy.

For strict pipelines:

```text
Asset
↓
approved QA
↓
similarity = duplicate
↓
BLOCK PUBLICATION
```

Do not rely only on frontend warnings.

Enforce server-side.

---

# 34. Similarity Review

Human reviewer must be able to compare:

```text
NEW ASSET
     ↔
SIMILAR ASSET
```

Side-by-side.

Show:

```text
SHA match

pHash distance

embedding similarity

same/different collection

same/different concept

generation lineage
```

Reviewer actions:

```text
MARK DISTINCT

CONFIRM NEAR DUPLICATE

CONFIRM DUPLICATE

SELECT CANONICAL
```

---

# 35. Human Override

Human decision must override automatic classification without deleting automatic evidence.

Persist:

```text
automaticClassification

humanClassification

finalClassification

reason

reviewedBy
reviewedAt
```

This later allows threshold calibration.

---

# 36. Semantic Similarity

CLIP-compatible embeddings allow semantic similarity.

Example:

```text
blue cybernetic wolf

neon robotic wolf
```

may have high semantic similarity despite visual differences.

Use this for:

```text
content discovery

collection organization

diversity analysis

concept repetition detection
```

Do NOT automatically classify semantic similarity as duplication.

---

# 37. Image-to-Text Shared Embedding Space

If the selected embedding model supports compatible text and image embeddings, design for:

```text
text embedding
        ↘
          shared vector space
        ↗
image embedding
```

This allows future:

```text
"cyber wolf"

↓ search

matching assets
```

TASK-05 should implement this if practical with the selected model, or at minimum design the abstraction so it can be added without schema redesign.

---

# 38. Concept Embeddings

Introduce optional:

```text
ConceptEmbedding
```

or a generic embedding abstraction if appropriate.

Embed:

```text
Concept title
+
description
+
key visual requirements
```

This enables detection of repeated concepts before image generation.

Do not tightly couple concept embedding to image embedding if the selected model requires different processing.

---

# 39. Prompt Similarity Foundation

TASK-03 contains canonical prompt snapshots.

Prepare ability to compare:

```text
new prompt
↔
historical prompts
```

Potential use:

```text
Prompt v12
similarity 0.97
Prompt v8
```

Do not treat prompt-text similarity as proof that resulting images will be duplicates.

Use it as one diversity signal.

---

# 40. Pre-Generation Diversity Guard

This is one of the most important TASK-05 features.

Before spending money:

```text
Concept
↓
Prompt
↓
Diversity Guard
↓
existing concepts/prompts/assets
↓
decision
```

Possible result:

```text
CLEAR

WARNING

HIGH_REPETITION_RISK
```

Do not automatically block all semantically similar ideas.

The guard should initially provide configurable warnings/blocking only for very strong cases.

---

# 41. Diversity Guard Context

Evaluate:

```text
concept similarity

prompt similarity

collection saturation

recent generation history

same subject

same style preset

same prompt version
```

Example:

```text
Last 50 Cyber Wolves generations:

42 use:
front-facing wolf
blue neon
black background
orange eyes
```

The system should detect low diversity.

---

# 42. Diversity Score

Unlike duplicate classification, collection diversity may use aggregate metrics.

If implementing a diversity score, document its exact mathematical definition.

Never create an unexplained:

```text
diversity = 87
```

Possible basis:

```text
mean pairwise embedding distance

nearest-neighbor distance distribution

cluster distribution
```

Expose underlying measurements.

---

# 43. Collection Clustering

Implement clustering for collection analysis.

Flow:

```text
Collection
↓
Asset embeddings
↓
clustering algorithm
↓
visual clusters
```

Initial algorithm may use:

```text
DBSCAN
```

or another appropriate unsupervised clustering method.

Choose based on actual requirements and embedding behavior.

Document the choice.

---

# 44. Why Clustering Exists

Example:

```text
Cyber Wolves
100 assets
```

Clustering might reveal:

```text
Cluster A
47 assets
front-facing blue wolf

Cluster B
31 assets
side-profile wolf

Cluster C
15 assets
full-body wolf

Outliers
7 assets
```

This tells the factory:

```text
Cluster A is overrepresented.
```

Future generation can intentionally explore underrepresented visual directions.

---

# 45. Cluster Model

Persist clustering runs rather than treating cluster membership as eternal truth.

Create:

```text
CollectionClusteringRun

id

collectionId

embeddingModel
embeddingModelVersion

algorithm
algorithmParameters

createdAt
```

Then:

```text
CollectionCluster

id
runId

clusterKey

centroid

memberCount
```

and:

```text
CollectionClusterMember

clusterId
assetId

distanceToCentroid
```

A new clustering algorithm/model creates a new run.

Do not overwrite historical runs.

---

# 46. Outliers

Support:

```text
OUTLIER
```

classification.

Outliers may be:

```text
valuable diversity
```

or:

```text
generation mistakes
```

Do not automatically reject them.

Expose them for analysis.

---

# 47. Collection Saturation

Create a collection-level diversity analysis.

Example output:

```json
{
  "assets": 100,
  "clusters": 4,
  "largestClusterShare": 0.47,
  "meanNearestNeighborSimilarity": 0.89,
  "nearDuplicatePairs": 18,
  "outliers": 7
}
```

This is much more useful than a vague "diversity score".

---

# 48. Generation Diversity Policy

Allow collection configuration:

```yaml
diversity:

  max-near-duplicate-rate: 0.15

  max-largest-cluster-share: 0.40

  recent-generation-window: 50

  warning-threshold: 0.90

  blocking-threshold: 0.97
```

Thresholds are initial configurable values.

Do not assume universal correctness.

---

# 49. Recent Generation Guard

The factory must avoid sequential repetition.

Before generating asset N:

```text
compare proposed concept/prompt
↓
last N generations
```

Recent history may receive stronger weighting than old library history.

Example:

```text
20 almost identical wolves generated in last hour
```

should trigger a diversity warning even if the total library is huge.

---

# 50. Batch Generation Protection

For requests like:

```text
generate 100 images
```

do not generate all 100 blindly.

Architecture should support staged execution:

```text
Generate batch 1
↓
Similarity analysis
↓
Diversity acceptable?
↓
Generate next batch
```

Example:

```text
100 requested

batch size = 10
```

After each batch:

```text
update similarity
update clusters
evaluate diversity
```

Then continue or pause according to policy.

---

# 51. Batch Pause

Support job state such as:

```text
PAUSED_DIVERSITY
```

or equivalent.

Example:

```text
Batch generation requested: 100

Generated: 40

Near duplicate rate exceeded threshold

Generation paused
```

Human may:

```text
CONTINUE

STOP

CHANGE PROMPT

CHANGE PRESET
```

Do not silently spend the remaining budget.

---

# 52. Integration With Prompt Engine

TASK-03 can help increase diversity.

When repetition is detected, return structured recommendations/context such as:

```text
overrepresented:
front-facing composition

overrepresented:
blue neon

underrepresented:
side profile

underrepresented:
full body

underrepresented:
environmental scenes
```

TASK-05 should produce the data.

Do not autonomously rewrite prompts yet unless explicitly part of existing generation policy.

A later task can build Diversity-Aware Prompt Generation.

---

# 53. Embedding Jobs

Create background jobs:

```text
GENERATE_ASSET_EMBEDDING

BACKFILL_ASSET_EMBEDDINGS

REINDEX_EMBEDDINGS

CLUSTER_COLLECTION

ANALYZE_COLLECTION_DIVERSITY
```

Reuse existing job architecture.

Support:

```text
retry
failure reason
attempt count
idempotency
```

---

# 54. Backfill

Existing assets created before TASK-05 need embeddings.

Implement safe backfill.

Example:

```text
10,000 assets

↓
batches of 100
```

Track progress.

Do not load all images into memory.

---

# 55. Reindexing

When changing embedding model:

```text
CLIP Model A
↓
Model B
```

support:

```text
REINDEX
```

without deleting Model A embeddings.

Flow:

```text
generate Model B embeddings
↓
build/query new vector index
↓
validate
↓
activate Model B
```

Preserve rollback capability where practical.

---

# 56. Active Embedding Model

Configuration:

```yaml
similarity:

  embedding:

    active-model:
      provider: local
      model: ...
      version: ...
```

Similarity comparisons must record which model generated them.

Never compare vectors from incompatible embedding spaces.

---

# 57. Embedding Normalization

If the selected model expects normalized embeddings for cosine similarity:

```text
L2 normalize
```

consistently.

Persist:

```text
normalized = true
```

Document preprocessing.

Tests must verify normalization assumptions.

---

# 58. Image Preprocessing

Embedding results depend on preprocessing.

Document:

```text
resize

crop behavior

color conversion

normalization

model-specific transforms
```

Use official/model-recommended preprocessing where possible.

Do not manually invent incompatible transformations.

---

# 59. Derived Variants

TASK-01 creates:

```text
master
thumbnail
Instagram
wallpaper
stock
```

Do NOT let derived variants pollute duplicate detection.

Similarity should primarily operate on:

```text
ORIGINAL / MASTER ASSETS
```

unless explicitly requested.

Otherwise:

```text
master.jpg
thumbnail.jpg
```

would naturally be detected as duplicates.

Define which Asset types participate.

---

# 60. Asset Scope

Configuration:

```yaml
similarity:

  asset-scope:

    include:
      - ORIGINAL
      - MASTER

    exclude:
      - THUMBNAIL
      - PREVIEW
      - DERIVED_VARIANT
```

Reuse actual project enums.

---

# 61. Cross-Collection Search

Support nearest-neighbor scopes:

```text
SAME_COLLECTION

PROJECT

GLOBAL
```

Example:

```text
stock image
```

may need global duplicate protection.

Wallpaper generation may prioritize same collection/project.

---

# 62. Similarity Search API

Implement:

```http
GET /api/v1/assets/{id}/similar
```

Parameters:

```text
scope

limit

minimumSimilarity

classification
```

Example response:

```json
{
  "assetId": "...",
  "results": [
    {
      "assetId": "...",
      "embeddingSimilarity": 0.96,
      "phashDistance": 5,
      "classification": "NEAR_DUPLICATE"
    }
  ]
}
```

---

# 63. Semantic Asset Search

If text/image shared embeddings are implemented:

```http
POST /api/v1/assets/search/semantic
```

Request:

```json
{
  "query": "dark cybernetic wolf with orange eyes",
  "limit": 30
}
```

Return matching assets using vector search.

This can become a very useful Media Factory feature beyond duplicate detection.

---

# 64. Duplicate Groups API

Implement:

```text
GET /api/v1/duplicate-groups

GET /api/v1/duplicate-groups/{id}

POST /api/v1/duplicate-groups/{id}/canonical

POST /api/v1/similarity-comparisons/{id}/confirm

POST /api/v1/similarity-comparisons/{id}/mark-distinct
```

Follow project REST conventions.

---

# 65. Collection Diversity API

Implement:

```http
GET /api/v1/collections/{id}/diversity
```

Return:

```text
asset count

cluster count

near-duplicate count

largest cluster

outliers

nearest-neighbor distribution

recent-generation repetition
```

Do not return only a single score.

---

# 66. Frontend — Similarity Explorer

Create:

```text
SIMILARITY
```

navigation area.

Pages:

```text
Duplicate Review

Similarity Explorer

Collection Diversity

Embedding Jobs
```

---

# 67. Similarity Explorer UI

Example:

```text
┌────────────────────────────────────────────────────────────┐
│ SIMILARITY EXPLORER                                        │
├─────────────────────┬──────────────────────────────────────┤
│                     │ Similar Assets                       │
│                     │                                      │
│   SOURCE IMAGE      │ [IMG]  98%  Near duplicate          │
│                     │ [IMG]  94%  Near duplicate          │
│                     │ [IMG]  87%  Similar                 │
│                     │ [IMG]  76%  Semantic similarity     │
└─────────────────────┴──────────────────────────────────────┘
```

Allow filters:

```text
collection

classification

minimum similarity

date

generation model
```

---

# 68. Side-by-Side Comparison

Clicking candidate should show:

```text
┌───────────────────────┬───────────────────────┐
│       ASSET A         │       ASSET B         │
│                       │                       │
│        IMAGE          │        IMAGE          │
│                       │                       │
├───────────────────────┴───────────────────────┤
│ SHA-256        different                      │
│ pHash distance 4                              │
│ Embedding      0.973                          │
│ Collection     same                           │
│ Concept        different                      │
│ Lineage        unrelated                      │
│                                               │
│ [Distinct] [Near Duplicate] [Duplicate]       │
└───────────────────────────────────────────────┘
```

---

# 69. Collection Diversity UI

Create visual cluster exploration.

Example:

```text
CYBER WOLVES

100 assets
4 clusters
18 near duplicates
7 outliers

Cluster 1
47 assets
███████████████████████

Cluster 2
31 assets
███████████████

Cluster 3
15 assets
███████

Outliers
7
███
```

Allow opening a cluster to inspect images.

---

# 70. Cluster Representative

For each cluster select/display representative images.

Possible representative:

```text
asset closest to centroid
```

Display several representatives where useful.

Do not generate a new image solely as cluster representation.

---

# 71. Visual Embedding Map — Optional

If practical, create a 2D visualization using dimensionality reduction such as:

```text
UMAP
```

or equivalent.

This is optional for TASK-05.

Do not block task completion on it.

If implemented, clearly label it as a visualization/projection, not the actual embedding space.

---

# 72. Dashboard Integration

Add:

```text
Exact Duplicates

Near Duplicates

Similarity Reviews Pending

Collection Diversity Warnings

Embedding Queue

Assets Without Embeddings
```

Useful operational visibility is more important than decorative charts.

---

# 73. Cost Tracking

Local embeddings may not incur API cost but still have computational cost.

TASK-05 must distinguish:

```text
external API cost
```

from:

```text
local compute
```

If external embedding providers are supported, integrate TASK-02 `GenerationCost`/operation-cost architecture.

Operation:

```text
IMAGE_EMBEDDING
```

Do not invent dollar costs for local GPU compute unless an explicit cost model exists.

---

# 74. Observability

Expose metrics:

```text
media_factory_embeddings_total

media_factory_embedding_failures_total

media_factory_embedding_duration

media_factory_similarity_queries_total

media_factory_similarity_query_duration

media_factory_exact_duplicates_total

media_factory_near_duplicates_total

media_factory_similarity_reviews_total

media_factory_collection_clustering_duration

media_factory_embedding_backlog
```

Useful tags:

```text
provider

model

model_version

classification

scope
```

Avoid:

```text
assetId

collectionId
```

as high-cardinality metric labels unless existing metrics conventions explicitly permit them.

---

# 75. Structured Logging

Log:

```text
fingerprint generated

embedding generated

similarity search completed

duplicate detected

near duplicate detected

human classification changed

collection clustering started

collection clustering completed

diversity guard triggered

batch generation paused
```

Include safe identifiers.

Do not log entire vectors at INFO level.

---

# 76. Performance Requirements

The architecture must remain viable for at least:

```text
100,000+ assets
```

without application-level O(N) comparisons for every new asset.

Use:

```text
indexed exact lookup

efficient pHash candidate strategy

pgvector ANN search

batch processing
```

Do not load the full asset library into memory.

---

# 77. pHash Search Scalability

Do not implement:

```text
SELECT every pHash
↓
compare in Java
```

for every new asset at large scale.

Choose/design a scalable candidate strategy.

Options may include:

```text
database-side bit operations

bucket/prefix candidate indexing

specialized representation

hybrid candidate filtering
```

Document the selected approach and its trade-offs.

A simple implementation may be acceptable initially if bounded and accompanied by a documented migration path before large scale.

---

# 78. Transaction Boundaries

Do not hold DB transactions while:

```text
loading image from object storage

running GPU embedding inference

calling external embedding API
```

Use short transactional boundaries.

Example:

```text
TX
create embedding job
COMMIT

↓

compute embedding

↓

TX
persist vector
COMMIT
```

---

# 79. Idempotency

Embedding generation must be idempotent for:

```text
asset
provider
model
modelVersion
```

If a duplicate job is picked:

```text
do not generate unnecessary second embedding
```

Use database constraints plus job idempotency where appropriate.

---

# 80. Failure Recovery

Support:

```text
embedding worker crashes

asset unavailable

model initialization fails

invalid image

DB unavailable

vector persistence failure
```

Failed embedding must not corrupt Asset state.

Allow retry where appropriate.

---

# 81. Missing Embeddings

An asset without embedding should remain usable.

State:

```text
PENDING

READY

FAILED
```

or equivalent.

Similarity system should clearly distinguish:

```text
not similar
```

from:

```text
similarity not evaluated
```

Never interpret missing embedding as uniqueness.

---

# 82. Model Upgrade Safety

When changing active model:

```text
Model A → Model B
```

do not mix:

```text
vector A
```

with:

```text
vector B
```

in similarity queries.

Queries must select compatible embeddings only.

Add explicit tests.

---

# 83. Security and Privacy

Embedding services may receive generated images.

If using external providers:

* document which provider receives media;
* reuse secret handling from TASK-02;
* never expose credentials;
* avoid logging image payloads;
* make provider choice configurable.

Local embedding mode should remain possible where practical.

---

# 84. Tests — Fingerprints

Test:

```text
identical file
→ same SHA-256

different bytes
→ different SHA-256

same visual recompressed
→ SHA differs
→ pHash remains close

significantly different image
→ larger pHash distance
```

Use deterministic test fixtures.

---

# 85. Tests — Embeddings

Test:

```text
embedding dimension

normalization

model identity

idempotent persistence

batch embedding

CPU mode
```

GPU-specific tests should not make CI require a GPU.

Mock the model/provider where necessary.

---

# 86. Tests — Vector Search

Create deterministic fixtures where:

```text
A ≈ B
A moderately similar C
A unrelated D
```

Verify nearest-neighbor ordering.

Do not rely on unstable real-world network models in CI.

Use fixed vectors for database/vector-search integration tests where appropriate.

---

# 87. Tests — Duplicate Classification

Test combinations:

```text
same SHA
→ EXACT_DUPLICATE
```

```text
different SHA
+
very small pHash distance
→ PERCEPTUAL_DUPLICATE
```

```text
different SHA
+
moderate pHash
+
very high embedding similarity
→ policy-dependent NEAR_DUPLICATE
```

```text
high semantic similarity
+
visually distinct
→ SIMILAR, not necessarily DUPLICATE
```

---

# 88. Tests — Human Override

Test:

```text
automatic = NEAR_DUPLICATE

human = DISTINCT

final = DISTINCT
```

Preserve automatic classification.

And:

```text
automatic = SIMILAR

human = DUPLICATE

final = DUPLICATE
```

Audit both.

---

# 89. Tests — Clustering

Use deterministic synthetic vectors.

Verify:

```text
known groups
→ expected clusters

outlier
→ OUTLIER
```

Do not write flaky clustering tests based on random vectors.

Set deterministic parameters.

---

# 90. Tests — Diversity Guard

Test:

```text
collection with diverse vectors
→ CLEAR
```

```text
collection dominated by one cluster
→ WARNING
```

```text
new concept nearly identical to recent generation
→ HIGH_REPETITION_RISK
```

Test thresholds through configuration.

---

# 91. Tests — Batch Generation Protection

Test:

```text
request 100 generations

batch size 10

similarity acceptable after batches 1-3

batch 4 crosses configured threshold

→ remaining generation paused
```

Ensure no additional generation jobs are dispatched after pause.

This test must use Mock providers and consume no paid API resources.

---

# 92. Integration Tests

Test complete flows.

### Exact duplicate

```text
Asset A
↓
Asset B identical
↓
SHA match
↓
duplicate finding
```

### Near duplicate

```text
Asset
↓
pHash
↓
embedding
↓
nearest neighbors
↓
near duplicate
↓
QA finding
```

### Unique

```text
Asset
↓
fingerprints
↓
embedding
↓
no meaningful candidates
↓
UNIQUE
```

### Human review

```text
near duplicate
↓
review
↓
MARK DISTINCT
↓
audit history
```

### Collection clustering

```text
Collection
↓
embeddings
↓
clustering
↓
clusters persisted
```

### Reindex

```text
Model A embeddings
↓
Model B backfill
↓
activate B
↓
queries use B only
```

---

# 93. pgvector Integration Tests

Use Testcontainers PostgreSQL with pgvector support.

Verify:

```text
extension installed

vector column

vector persistence

nearest-neighbor query

index creation

distance semantics
```

Do not replace actual database integration with mocks for these tests.

---

# 94. Documentation

Create:

```text
docs/similarity-engine.md

docs/image-embeddings.md

docs/duplicate-detection.md

docs/collection-clustering.md

docs/diversity-guard.md
```

---

# 95. Similarity Engine Documentation

Explain:

```text
SHA-256
↓
pHash
↓
Embedding
↓
Vector Search
↓
Policy
```

Clearly document the difference between:

```text
exact duplicate

perceptual duplicate

near duplicate

visual similarity

semantic similarity
```

---

# 96. Embedding Documentation

Document:

```text
provider

model

version

dimension

preprocessing

normalization

distance metric

pgvector index

batch size

CPU/GPU behavior
```

This information is required for reproducibility.

---

# 97. Duplicate Detection Documentation

Explain:

```text
classification rules

thresholds

profiles

human override

duplicate groups

canonical assets

publication blocking
```

Explicitly state that embedding similarity alone is not definitive proof of duplication.

---

# 98. Clustering Documentation

Explain:

```text
algorithm

parameters

embedding model

cluster run versioning

centroids

outliers

collection saturation metrics
```

Explain that clusters are analytical groupings, not absolute semantic truth.

---

# 99. Diversity Guard Documentation

Explain:

```text
pre-generation checks

recent-generation checks

collection saturation

batch generation pause

warning/block behavior
```

Document how this protects:

```text
API budget
content diversity
stock portfolio quality
```

---

# 100. Definition of Done

TASK-05 is complete when:

```text
Asset
↓
SHA-256
↓
pHash
↓
CLIP-compatible embedding
↓
pgvector
↓
nearest-neighbor search
↓
structured similarity classification
```

works end-to-end.

And:

```text
100 collection assets
↓
clustering
↓
visual families
↓
diversity analysis
```

works.

And:

```text
new asset
↓
near duplicate
↓
QA finding
↓
human side-by-side review
↓
confirm duplicate / mark distinct
```

works.

And:

```text
large generation batch
↓
repetition increases
↓
Diversity Guard
↓
remaining batch pauses
```

works.

---

# 101. Verification

Before completing TASK-05:

1. run backend unit tests;
2. run integration tests;
3. run frontend tests;
4. run backend production build;
5. run frontend production build;
6. apply Flyway migrations;
7. verify pgvector extension;
8. verify vector indexes;
9. generate SHA-256 for an asset;
10. generate pHash;
11. generate local embedding;
12. verify embedding persistence;
13. verify vector normalization;
14. perform nearest-neighbor query;
15. test exact duplicate;
16. test recompressed perceptual duplicate;
17. test near duplicate;
18. test semantically similar but visually distinct assets;
19. verify QA integration;
20. verify publication guard;
21. verify human duplicate override;
22. verify duplicate group creation;
23. verify canonical asset selection;
24. cluster a collection;
25. inspect cluster representatives;
26. verify outlier detection;
27. verify collection diversity API;
28. test Diversity Guard;
29. test recent-generation repetition;
30. test batch-generation pause;
31. backfill embeddings for existing assets;
32. simulate embedding worker failure;
33. verify retry;
34. verify CPU fallback;
35. verify model-version isolation;
36. test embedding reindex;
37. verify old embeddings remain available;
38. verify no derived thumbnails pollute duplicate detection;
39. verify no paid external API is required for automated tests;
40. verify no full vector payloads are unnecessarily exposed through frontend APIs.

---

# 102. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Asset
→ Fingerprints
→ Embedding
→ pgvector
→ Candidate Search
→ Similarity Policy
→ QA
```

## Fingerprints

Report:

```text
SHA-256 implementation

pHash implementation

distance calculation
```

## Embeddings

Report exact:

```text
provider

model

model version

dimension

preprocessing

normalization

CPU/GPU support
```

Do not report generic "CLIP" without exact implementation details.

## pgvector

Report:

```text
extension

column type

distance metric

index type

query strategy
```

## Similarity Classification

Document implemented thresholds and profiles.

## Duplicate Groups

Explain:

```text
group creation

canonical selection

human override
```

## Collection Clustering

Report:

```text
algorithm

parameters

cluster statistics

outlier handling
```

## Diversity Guard

Explain:

```text
pre-generation check

recent generation analysis

batch protection

pause behavior
```

## Performance

Report tests/measurements for realistic dataset sizes if performed.

Never invent benchmark numbers.

## Frontend

List implemented:

```text
Similarity Explorer

Duplicate Review

Collection Diversity

Embedding Jobs
```

## Tests

Report:

```text
unit tests

integration tests

pgvector tests

frontend tests

production builds
```

## Remaining Limitations

Explicitly list:

```text
thresholds requiring calibration

embedding model limitations

clustering limitations

scaling considerations

future improvements
```

---

# Engineering Principles

Throughout TASK-05 follow these rules:

1. Exact duplication, perceptual duplication, visual similarity, and semantic similarity are different concepts.
2. Never rely on embedding similarity alone to declare a duplicate.
3. SHA-256 should be checked before expensive similarity operations.
4. Do not perform O(N) application-level comparisons for every asset.
5. Use pgvector for vector retrieval.
6. Never compare vectors from incompatible embedding models.
7. Embedding model identity and version must always be stored.
8. Image preprocessing must be documented and reproducible.
9. Historical embeddings are immutable.
10. Model upgrades create new embeddings rather than overwriting old ones.
11. Human decisions never erase automatic similarity evidence.
12. Duplicate assets are not automatically deleted.
13. Derived thumbnails/variants must not pollute master-asset similarity analysis.
14. Similarity thresholds must be configurable.
15. Similarity policies may differ by pipeline.
16. Collection context matters.
17. Generation lineage matters.
18. Semantic similarity does not automatically mean duplication.
19. Missing embeddings do not mean an asset is unique.
20. Clustering results are analytical, not absolute truth.
21. Diversity metrics must have documented mathematical meaning.
22. Avoid unexplained aggregate scores.
23. Batch generation should be interruptible when diversity collapses.
24. Protect generation budget from repetitive content.
25. Prefer local embedding inference where practical.
26. GPU acceleration must be optional.
27. Automated CI must not require a GPU.
28. Automated tests must not consume paid AI APIs.
29. Do not hold database transactions during embedding inference.
30. Build the system for at least 100,000+ assets without fundamental redesign.
31. Preserve data required for future threshold calibration.
32. Design outputs so TASK-10 Analytics can compare similarity, quality, cost, and content performance.
33. Optimize Media Factory for useful portfolio diversity rather than maximum raw generation volume.

The result of TASK-05 should transform Media Factory from a system that merely detects identical files into a similarity-aware content production platform capable of understanding visual repetition, semantic relationships, collection structure, and generation diversity before unnecessary generation budget is spent.
