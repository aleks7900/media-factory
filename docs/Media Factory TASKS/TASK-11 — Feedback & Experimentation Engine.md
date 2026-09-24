# TASK-11 — Feedback & Experimentation Engine

## Objective

Build a production-ready **Feedback & Experimentation Engine** for Media Factory.

The engine must analyze historical production and performance data to determine which **visual attributes and generation decisions correlate with successful assets**, convert those observations into explicit hypotheses, and create the next generation of controlled experiments.

Target feedback loop:

```text
GENERATE
   ↓
QA
   ↓
PROCESS
   ↓
PUBLISH
   ↓
COLLECT ANALYTICS
   ↓
UNDERSTAND VISUAL ATTRIBUTES
   ↓
FIND PERFORMANCE PATTERNS
   ↓
GENERATE HYPOTHESES
   ↓
DESIGN EXPERIMENTS
   ↓
HUMAN APPROVAL
   ↓
RUN CONTROLLED EXPERIMENTS
   ↓
MEASURE
   ↓
LEARN
   ↺
```

TASK-11 must answer questions such as:

```text
Which visual attributes appear frequently in high-performing assets?

Which attributes correlate with downloads?

Which attributes correlate with revenue?

Which attributes correlate with QA rejection?

Which prompt variables correlate with stronger results?

Which styles work better for AMOLED wallpapers?

Which compositions work better for stock?

Which visual clusters are saturated?

Which concepts are over-produced?

Which combinations have not been tested enough?

Which expensive providers actually produce different downstream results?

What experiment should Media Factory run next?
```

The system must **not** autonomously rewrite production prompts or continuously optimize itself without controls.

Instead:

```text
OBSERVATION
↓
HYPOTHESIS
↓
EXPERIMENT PROPOSAL
↓
HUMAN APPROVAL
↓
VERSIONED EXPERIMENT
↓
RESULTS
```

---

# 1. Inspect TASK-01 → TASK-10 First

Before implementation inspect the current repository and reuse existing architecture.

TASK-11 depends heavily on:

```text
TASK-03
Prompt Engine
PromptVersion
PromptVariable
PromptPreset
PromptExperiment
PromptExperimentVariant

TASK-04
Visual QA
Visual findings
Prompt compliance
Human override

TASK-05
Image embeddings
Similarity
Clusters

TASK-06
Processing profiles

TASK-07
Wallpaper production
AMOLED collections

TASK-08
Stock Factory

TASK-09
Video Factory

TASK-10
Analytics
Performance metrics
Revenue
Costs
Attribution
```

Do not build a second experiment system.

Extend TASK-03 experimentation architecture.

Do not build a second analytics system.

Consume TASK-10.

---

# 2. Core Architecture

Target:

```text
             TASK-10 ANALYTICS
                    │
                    ▼
          Performance Dataset
                    │
       ┌────────────┴────────────┐
       ▼                         ▼
Visual Attributes         Production Context
       │                         │
       └────────────┬────────────┘
                    ▼
             Feature Dataset
                    │
                    ▼
             Pattern Engine
                    │
                    ▼
               Findings
                    │
                    ▼
          Hypothesis Engine
                    │
                    ▼
        Experiment Proposals
                    │
                    ▼
             Human Review
                    │
                    ▼
       PromptExperiment TASK-03
                    │
                    ▼
            New Generations
                    │
                    ▼
              TASK-10
                    │
                    ↺
```

---

# 3. Separate Facts, Findings and Hypotheses

This distinction is mandatory.

## Fact

Example:

```text
Asset A

downloads = 4,281
revenue = $14.20
background = black
dominant style = neon
subject = wolf
composition = centered
```

## Finding

Example:

```text
Within Collection X during the last 90 days,
assets tagged "dark_background" had a higher median
download rate than assets without that attribute.
```

## Hypothesis

Example:

```text
Increasing dark-background variants in the next
Cyber Wolf experiment may improve wallpaper download rate.
```

## Experiment

Example:

```text
Control:
current background distribution

Variant:
80% black/dark backgrounds
```

Never represent a correlation directly as proven causality.

---

# 4. Visual Attribute Model

Create:

```text
VisualAttribute
```

A visual attribute describes a reusable semantic property of an asset.

Examples:

```text
dark_background

black_background

high_contrast

low_contrast

centered_subject

off_center_subject

symmetrical

asymmetrical

close_up

medium_shot

wide_shot

single_subject

multiple_subjects

minimal

detailed

neon

cinematic

photorealistic

illustration

fantasy

cyberpunk

warm_palette

cool_palette

monochrome

red_accent

blue_accent

purple_accent

glowing_eyes

particles

fog

rain

bokeh

negative_space

copy_space

dramatic_lighting

soft_lighting
```

Do not hardcode all attributes as database columns.

---

# 5. Attribute Taxonomy

Create:

```text
VisualAttributeDefinition
```

Suggested fields:

```text
id

key
name
description

category

valueType

allowedValues

active

version

createdAt
updatedAt
```

Categories may include:

```text
SUBJECT

COMPOSITION

COLOR

LIGHTING

STYLE

BACKGROUND

CAMERA

MOOD

DETAIL

MOTION

COMMERCIAL

TECHNICAL
```

---

# 6. Attribute Value Types

Support:

```text
BOOLEAN

ENUM

NUMBER

STRING
```

Examples:

```text
centered_subject = true

shot_type = CLOSE_UP

subject_count = 1

brightness = 0.21

dominant_color = BLACK
```

---

# 7. Asset Visual Features

Create:

```text
AssetVisualFeature
```

Suggested:

```text
id

assetId

attributeDefinitionId

value

confidence

source

extractorVersion

createdAt
```

Possible sources:

```text
VISION_MODEL

COMPUTER_VISION

PROMPT

MANUAL

DERIVED
```

---

# 8. Feature Provenance

Every feature must record where it came from.

Example:

```text
attribute:
glowing_eyes

value:
true

source:
VISION_MODEL

model:
...

confidence:
0.94

extractorVersion:
visual-features-v3
```

Do not treat model-generated labels as ground truth.

---

# 9. Visual Feature Extraction Pipeline

Target:

```text
Approved Asset
      ↓
Deterministic CV Features
      +
Vision Semantic Features
      +
Prompt Metadata
      +
QA Metadata
      ↓
Normalized Visual Feature Set
```

---

# 10. Deterministic Features

Use local deterministic algorithms where practical.

Examples:

```text
brightness

contrast

saturation

dominant colors

entropy

edge density

subject bounding-box ratio

subject position

negative-space ratio

aspect ratio
```

Do not use Vision API for properties that can be measured reliably locally.

---

# 11. Semantic Features

Use existing `VisionProvider` architecture for semantic attributes.

Examples:

```text
subject type

shot type

visual style

mood

background type

lighting type

composition type

environment

presence of particles

presence of fog

presence of rain

presence of copy space
```

Require structured output.

---

# 12. Visual Feature Extraction Prompt

Create a versioned TASK-03 prompt template:

```text
VISUAL_ATTRIBUTE_EXTRACTION
```

Vision model should return structured JSON matching a schema.

Example:

```json
{
  "subject": {
    "type": "wolf",
    "count": 1
  },
  "composition": {
    "centered": true,
    "symmetrical": true,
    "shotType": "CLOSE_UP"
  },
  "style": {
    "primary": "CYBERPUNK",
    "secondary": ["NEON"]
  },
  "lighting": {
    "type": "DRAMATIC",
    "contrast": "HIGH"
  },
  "background": {
    "type": "DARK",
    "complexity": "LOW"
  },
  "effects": [
    "PARTICLES",
    "FOG"
  ]
}
```

Validate response strictly.

---

# 13. Attribute Extraction Versioning

Visual feature extraction evolves.

Store:

```text
extractorVersion

visionProvider

visionModel

promptVersion
```

Never silently reinterpret old feature records using a new extractor.

---

# 14. Re-Extraction

Support:

```text
ReExtractVisualFeaturesJob
```

for:

```text
asset

collection

date range

extractor version
```

Do not overwrite historical extraction results.

Create a new version.

---

# 15. Human Attribute Override

Allow user to correct extracted features.

Example:

```text
AI:
shot_type = MEDIUM

Human:
shot_type = CLOSE_UP
```

Store:

```text
original value

override value

reason

user

timestamp
```

Human correction must not be overwritten by later extraction.

---

# 16. Prompt-Derived Features

Some features are known from generation inputs.

Example:

```text
PromptPreset = AMOLED

variable:
background = black

variable:
style = cyberpunk
```

Record these separately from observed visual properties.

Distinguish:

```text
REQUESTED_ATTRIBUTE
```

from:

```text
OBSERVED_ATTRIBUTE
```

This allows analysis of prompt compliance.

---

# 17. Requested vs Observed

Example:

```text
requested:
black_background = true

observed:
black_background = false
```

This becomes useful for:

```text
prompt compliance

provider comparison

model comparison
```

---

# 18. Feature Dataset

Create:

```text
FeedbackDatasetBuilder
```

It must join:

```text
Asset

Visual Features

Concept

Prompt Version

Prompt Variables

Prompt Presets

Experiment Variant

Provider

Model

QA

Similarity Cluster

Processing Profile

Publication

Analytics
```

into an analysis dataset.

---

# 19. Dataset Grain

Default analysis grain:

```text
one row per asset
```

with references to relevant features.

For platform-specific analysis:

```text
one row per asset/publication
```

may be required.

Be explicit.

---

# 20. Time Windows

Support:

```text
7D

30D

90D

180D

LIFETIME

CUSTOM
```

Performance analysis must always include a defined observation period.

---

# 21. Age-Normalized Performance

Do not compare:

```text
asset published yesterday
```

directly with:

```text
asset published 8 months ago
```

using lifetime downloads.

Support metrics such as:

```text
downloadsFirst7Days

downloadsFirst30Days

viewsFirst7Days

revenueFirst30Days
```

where data exists.

---

# 22. Minimum Observation Window

Experiment/feedback analysis should be configurable.

Example:

```yaml
feedback:
  minimum-observation-days: 7
```

Do not generate strong conclusions from assets published minutes ago.

---

# 23. Minimum Sample Size

Create configurable guards:

```yaml
feedback:
  minimum-sample-size: 20
```

Findings below threshold may be marked:

```text
INSUFFICIENT_DATA
```

Do not hide them, but do not present them with false confidence.

---

# 24. Performance Targets

Support analysis against explicit metrics:

```text
DOWNLOADS

DOWNLOAD_RATE

LIKES

LIKE_RATE

REVENUE

PROFIT

ROI

QA_APPROVAL_RATE

COST_PER_APPROVED_ASSET
```

No single universal “success” score.

---

# 25. Analysis Context

Every analysis must specify:

```text
project

collection

asset type

platform

date range

performance metric
```

Optional:

```text
provider

model

prompt

style

cluster
```

---

# 26. Pattern Engine

Create:

```text
VisualPatternAnalysisService
```

Responsibilities:

```text
group assets by feature

calculate performance distributions

compare groups

control obvious confounders where possible

calculate sample sizes

calculate effect sizes

calculate uncertainty

persist findings
```

---

# 27. Do Not Use Only Averages

For skewed metrics such as:

```text
downloads

revenue
```

calculate:

```text
count

mean

median

p25

p75

p90
```

where useful.

A few viral assets must not completely distort interpretation.

---

# 28. Feature Performance

Example output:

```text
Feature:
dark_background = true

Assets:
142

Median 30D downloads:
381

Comparison group:
dark_background = false

Assets:
119

Median 30D downloads:
214
```

Store actual statistics.

---

# 29. Effect Size

Where appropriate calculate explicit effect size.

Do not only output:

```text
+78%
```

without sample/context.

Store:

```text
absolute difference

relative difference

sample size

confidence/uncertainty
```

---

# 30. Statistical Analysis

Implement simple, explainable statistics first.

Potential methods:

```text
difference in means

difference in medians

bootstrap confidence intervals

correlation

rank correlation

proportion comparison
```

Do not build opaque ML prematurely.

---

# 31. Multiple Comparisons

The system may test many attributes.

Account for the risk of false discoveries.

At minimum:

```text
record number of tested hypotheses

mark exploratory findings

apply configurable multiple-testing correction
```

where formal significance tests are used.

---

# 32. Finding Confidence

Create:

```text
FindingConfidence
```

Possible states:

```text
LOW

MEDIUM

HIGH
```

based on explicit deterministic rules such as:

```text
sample size

effect magnitude

uncertainty

data quality

consistency across periods
```

Document the calculation.

Do not let an LLM invent confidence scores.

---

# 33. Finding Status

Create:

```text
DISCOVERED

REVIEWED

ACCEPTED_FOR_EXPERIMENT

DISMISSED

EXPERIMENT_CREATED

STALE
```

---

# 34. Feedback Finding

Create:

```text
FeedbackFinding
```

Suggested fields:

```text
id

analysisRunId

attributeKey
attributeValue

targetMetric

scope

sampleSize
comparisonSampleSize

baselineValue
observedValue

absoluteDifference
relativeDifference

confidence

status

periodStart
periodEnd

createdAt
```

---

# 35. Finding Evidence

Store supporting evidence separately.

Example:

```text
FeedbackFindingEvidence

findingId

assetId

role
```

Roles:

```text
POSITIVE_EXAMPLE

NEGATIVE_EXAMPLE

OUTLIER
```

Do not copy full asset metadata into finding.

---

# 36. Multi-Attribute Analysis

Single attributes are not enough.

Support combinations such as:

```text
dark_background
+
centered_subject
+
neon
```

But prevent combinatorial explosion.

---

# 37. Combination Limits

Configure:

```text
maximumFeatureCombinationSize = 2
```

initially.

Potentially:

```text
3
```

later.

Do not search every arbitrary combination.

---

# 38. Candidate Combination Selection

Analyze combinations only when:

```text
sufficient sample size

attributes individually relevant

business/domain interest exists
```

Avoid millions of meaningless combinations.

---

# 39. Example Combination Finding

```text
Scope:
AMOLED wallpapers

Features:
dark_background = true
centered_subject = true

Sample:
84 assets

Metric:
30D download rate

Observed:
8.4%

Comparison:
5.1%
```

Represent as descriptive evidence.

---

# 40. Continuous Feature Analysis

Some attributes are numeric:

```text
brightness

contrast

saturation

subjectSize

negativeSpace
```

Support:

```text
correlation

quantile buckets
```

Example:

```text
brightness:

0–20%
20–40%
40–60%
60–80%
80–100%
```

---

# 41. Avoid Arbitrary Buckets

Bucket definitions must be:

```text
versioned

documented

configurable
```

Do not silently change them between analyses.

---

# 42. Color Analysis

Support:

```text
dominant color

palette family

average luminance

saturation
```

For AMOLED, include:

```text
darkPixelRatio
```

Example:

```text
percentage of pixels below configured luminance threshold
```

This should be deterministic.

---

# 43. AMOLED-Specific Analysis

Analyze:

```text
darkPixelRatio

blackBackground

contrast

neonAccent

subjectSize

negativeSpace

dominantColor
```

against:

```text
downloads

likes

download rate
```

within AMOLED collections.

Do not generalize AMOLED findings automatically to stock images.

---

# 44. Stock-Specific Analysis

Stock analysis may focus on:

```text
copy space

subject count

commercial neutrality

background simplicity

orientation

composition

photorealism

text absence

brand absence
```

against:

```text
acceptance

downloads

revenue
```

---

# 45. Video-Specific Analysis

TASK-09 may expose:

```text
motion intensity

camera motion

loop strategy

video duration

fps

motion plan

flicker score
```

Analyze against:

```text
views

likes

downloads

QA approval
```

where data exists.

---

# 46. QA Feedback

TASK-04 QA is itself valuable feedback.

Analyze:

```text
attribute
→ QA rejection rate
```

Example:

```text
close-up human hands
→ higher anatomy rejection
```

or:

```text
high particle density
→ artifact rejection
```

Do not treat QA outcome as user-market performance; keep metrics distinct.

---

# 47. Prompt Compliance Feedback

Analyze:

```text
requested attribute
vs
observed attribute
```

by:

```text
provider

model

prompt version
```

Example:

```text
Provider A
requested centered subject
observed centered subject
92%

Provider B
76%
```

This measures prompt compliance.

---

# 48. Provider Feedback

Analyze provider/model across:

```text
QA approval

prompt compliance

cost

downloads

revenue
```

Do not collapse them into one magic provider score.

---

# 49. Prompt Variable Analysis

TASK-03 variables must become analysis dimensions.

Example:

```text
camera_angle

background

subject

lighting

style

color_accent
```

Measure performance by values.

---

# 50. Prompt Preset Analysis

Analyze:

```text
AMOLED

CINEMATIC

NEON

MINIMAL

PHOTOREALISTIC

FANTASY
```

against relevant metrics.

---

# 51. Prompt Version Analysis

A new prompt version must remain analytically distinguishable.

Never merge:

```text
Prompt v3
Prompt v4
```

simply because they belong to the same template.

---

# 52. Similarity Cluster Feedback

Use TASK-05 cluster membership.

Analyze:

```text
cluster size

cluster internal similarity

downloads per asset

revenue per asset

marginal value of additional assets
```

---

# 53. Saturation Detection

Create:

```text
SaturationAnalysisService
```

Goal:

identify groups where Media Factory keeps producing very similar assets but additional assets show diminishing performance.

Example:

```text
Cluster:
Neon blue wolf close-up

Assets:
63

First 10:
high downloads

Next 20:
moderate

Last 33:
low
```

This may indicate saturation.

---

# 54. Saturation Finding

Possible output:

```text
clusterId

assetCount

recentAssetCount

averageSimilarity

performanceByGenerationOrder

marginalPerformanceTrend

confidence
```

Do not automatically stop generation solely from this finding in TASK-11.

---

# 55. Diversity Analysis

Measure diversity within:

```text
collection

generation batch

experiment
```

using TASK-05 embeddings.

Possible metrics:

```text
average pairwise similarity

cluster count

largest cluster ratio

outlier ratio
```

---

# 56. Diversity vs Performance

Analyze whether diversity correlates with:

```text
collection downloads

coverage

revenue
```

without assuming more diversity is always better.

---

# 57. Novelty

Create contextual:

```text
NoveltyScore
```

based on embedding distance from existing collection/catalog.

Use only as an analysis feature.

Do not treat novelty as quality.

---

# 58. Production Efficiency Findings

TASK-11 should identify patterns such as:

```text
Feature X
→ strong market performance
→ but very high QA rejection
```

or:

```text
Provider B
→ similar downloads
→ lower generation cost
```

Represent individual dimensions, not an opaque recommendation score.

---

# 59. Hypothesis Engine

Create:

```text
HypothesisGenerationService
```

Input:

```text
FeedbackFinding[]
```

Output:

```text
ExperimentHypothesis[]
```

---

# 60. Experiment Hypothesis

Create:

```text
ExperimentHypothesis
```

Suggested fields:

```text
id

findingId

title

description

scope

targetMetric

controlDefinition

treatmentDefinition

rationale

minimumSampleSize

observationWindow

estimatedGenerationCount

estimatedCost

status

createdAt
```

---

# 61. Hypothesis Status

Use:

```text
DRAFT

PROPOSED

APPROVED

REJECTED

CONVERTED_TO_EXPERIMENT

ARCHIVED
```

---

# 62. Hypothesis Example

```text
Title:
Dark background test for Cyber Wolves

Observation:
Dark-background Cyber Wolf wallpapers showed a higher
30-day median download rate in the analyzed dataset.

Hypothesis:
Increasing dark-background usage may improve 30-day
download rate for this collection.

Control:
Current Cyber Wolf prompt version.

Treatment:
Same prompt with:
background = BLACK
darkPixelRatio target increased.

Primary Metric:
30D download rate

Secondary Metrics:
QA approval
cost per approved asset
likes

Sample:
50 control
50 treatment
```

---

# 63. LLM Role

An LLM may help:

```text
summarize findings

formulate human-readable hypotheses

suggest experiment structure
```

But deterministic/statistical calculations must be performed in code.

Never ask an LLM:

```text
"Which feature is statistically significant?"
```

and trust its answer without calculated data.

---

# 64. Hypothesis Prompt

Create TASK-03 versioned prompt:

```text
FEEDBACK_HYPOTHESIS_GENERATION
```

Input must contain structured calculated findings.

Output must conform to structured schema.

---

# 65. Hypothesis Guardrails

Do not create experiment proposal when:

```text
sample too small

data quality invalid

metric unavailable

finding stale

required prompt variable cannot be controlled
```

unless explicitly marked exploratory.

---

# 66. Experiment Proposal Engine

Create:

```text
ExperimentProposalService
```

Converts approved hypotheses into TASK-03-compatible experiment drafts.

---

# 67. Reuse PromptExperiment

Do not create:

```text
FeedbackExperiment
```

if TASK-03 already has:

```text
PromptExperiment
```

Extend it with references:

```text
sourceHypothesisId

sourceFindingIds
```

if necessary.

---

# 68. Experiment Types

Support:

```text
PROMPT_VARIABLE

PROMPT_VERSION

PRESET

VISUAL_ATTRIBUTE_TARGET

PROVIDER

MODEL

PROCESSING_PROFILE
```

Start with the types that existing architecture can safely control.

---

# 69. A/B First

Initial Feedback Engine should primarily generate:

```text
A/B experiments
```

Example:

```text
A = current production baseline
B = one controlled change
```

Avoid changing five unrelated dimensions simultaneously.

---

# 70. One Primary Variable

Default experiment policy:

```text
one primary changed variable
```

Secondary differences should be minimized.

This makes results interpretable.

---

# 71. Multi-Variant Experiments

Architecture may support:

```text
A/B/C
```

through TASK-03.

But Feedback Engine should default to simple experiments unless there is a clear reason otherwise.

---

# 72. Control Group

Control must reference an immutable:

```text
PromptVersion

PresetVersion

Provider configuration snapshot

ProcessingProfileVersion
```

where relevant.

Do not define control as “whatever production currently uses”.

---

# 73. Treatment

Treatment must also be versioned.

Example:

```text
Prompt v12
+
background = BLACK
```

or a new prompt version generated specifically for the experiment.

---

# 74. Prompt Version Creation

Feedback Engine must never edit a published PromptVersion.

If an experiment requires prompt modification:

```text
existing PromptVersion
↓
new DRAFT
↓
change
↓
validation
↓
publish
↓
experiment variant
```

Follow TASK-03 rules.

---

# 75. Experiment Budget

Every proposed experiment must estimate:

```text
generation count

generation cost

QA cost

processing cost
```

where known.

Store:

```text
estimatedCost
```

and:

```text
maxBudget
```

---

# 76. Experiment Budget Guard

When executing:

```text
actual experiment cost >= maxBudget
```

stop/pause further generation.

Do not exceed budget silently.

---

# 77. Sample Size

Experiment proposal must define:

```text
minimum sample

target sample
```

Do not invent false statistical precision.

Start with configurable sample policies.

---

# 78. Observation Window

Every experiment defines:

```text
observationWindow
```

Example:

```text
30 days after publication
```

or:

```text
first 7 days
```

Do not compare variants with different exposure windows without normalization.

---

# 79. Primary Metric

Every experiment must have exactly one:

```text
primaryMetric
```

Examples:

```text
30D_DOWNLOAD_RATE

30D_REVENUE

QA_APPROVAL_RATE
```

---

# 80. Secondary Metrics

Allow:

```text
QA approval

generation cost

cost per approved asset

likes

revenue

downloads
```

as secondary metrics.

Do not redefine experiment success after results are seen.

---

# 81. Experiment Registration

Store experiment plan before first generation.

Persist:

```text
hypothesis

variants

primary metric

secondary metrics

sample target

budget

observation window

start criteria

completion criteria
```

This prevents hindsight rewriting.

---

# 82. Experiment Execution

Use existing production pipelines.

Example:

```text
Experiment
↓
Generation Assignment
↓
TASK-03 Prompt
↓
TASK-02 Provider
↓
TASK-04 QA
↓
TASK-05 Similarity
↓
TASK-06 Processing
↓
TASK-07/08/09
↓
Publication
↓
TASK-10 Analytics
```

Do not create a separate “experiment generation pipeline”.

---

# 83. Experiment Isolation

Generation must retain:

```text
experimentId

variantId
```

through:

```text
Asset
Publication
Analytics
```

so downstream metrics remain attributable.

---

# 84. Deterministic Assignment

Reuse TASK-03 deterministic variant assignment.

Do not use:

```java
Math.random()
```

for experiment assignment.

---

# 85. Prevent Experiment Contamination

Do not allow the same logical test asset to silently switch variant.

Once assigned:

```text
variantId
```

remains immutable.

---

# 86. Similarity During Experiments

TASK-05 can accidentally remove one experiment variant disproportionately.

Track:

```text
generated

duplicate-rejected

QA-rejected

published
```

per variant.

Experiment analysis must expose this funnel.

---

# 87. Publication Exposure

Where possible track whether experiment variants received comparable publication exposure.

Example:

```text
Variant A
100 published

Variant B
38 published
```

Raw total downloads are not directly comparable.

Use rates/normalized windows where appropriate.

---

# 88. Experiment Lifecycle

Use/extend:

```text
DRAFT

READY_FOR_REVIEW

APPROVED

RUNNING

GENERATION_COMPLETE

OBSERVING

READY_FOR_ANALYSIS

COMPLETED

PAUSED

CANCELLED
```

Map cleanly onto existing TASK-03 statuses rather than duplicating if possible.

---

# 89. Human Approval

No experiment generated by Feedback Engine should automatically start paid generation by default.

Flow:

```text
Feedback Engine
↓
Experiment Proposal
↓
Human Review
↓
Approve
↓
Experiment created/started
```

---

# 90. Approval UI

Show:

```text
Finding

Evidence

Hypothesis

Control

Treatment

Primary metric

Sample size

Estimated cost

Maximum budget

Observation period
```

Actions:

```text
APPROVE

EDIT

REJECT

ARCHIVE
```

---

# 91. Auto-Experiment Mode

Architecture may support future:

```text
AUTO_APPROVE
```

but TASK-11 default must be:

```text
HUMAN_APPROVAL_REQUIRED
```

If configuration is added, keep auto mode disabled by default.

---

# 92. Finding Review UI

Create:

```text
FEEDBACK
```

navigation section.

Pages:

```text
Overview

Visual Attributes

Findings

Hypotheses

Experiment Proposals

Experiments

Saturation

Data Quality
```

---

# 93. Feedback Overview

Show:

```text
New findings

High-confidence findings

Hypotheses awaiting review

Experiments awaiting approval

Running experiments

Experiments observing

Completed experiments

Stale findings
```

---

# 94. Visual Attribute Explorer

Allow selecting:

```text
attribute

value

collection

asset type

platform

metric

date range
```

Display:

```text
sample size

performance distribution

comparison group

effect size

confidence interval

example assets
```

---

# 95. Visual Examples

For a finding display representative:

```text
high-performing assets

comparison assets

outliers
```

using thumbnails.

This is important because numerical labels may hide visual nuances.

---

# 96. Attribute Correlation Matrix

Optionally show relationships between selected attributes and metrics.

Do not create an enormous unreadable matrix of every possible feature.

Allow filtered subsets.

---

# 97. Scatter Plots

Useful examples:

```text
darkPixelRatio
vs
downloadsFirst30Days

subjectSize
vs
downloadRate

similarityToClusterCentroid
vs
revenue
```

---

# 98. Distribution Charts

Use:

```text
histogram

box plot

quantiles
```

for performance comparisons.

Avoid showing only average bars.

---

# 99. Finding Detail

Show:

```text
Finding

Scope

Metric

Sample

Comparison

Effect

Uncertainty

Data-quality warnings

Representative assets

Related findings

Generated hypotheses
```

---

# 100. Saturation Dashboard

For clusters show:

```text
cluster

asset count

recent generation count

average similarity

downloads per asset

revenue per asset

generation cost

performance over generation order
```

---

# 101. Saturation Experiment

A finding such as:

```text
Cluster appears saturated
```

could create hypothesis:

```text
Reducing production of this cluster and allocating
generation budget to a more diverse treatment may improve
revenue per generated asset.
```

Experiment must test this rather than automatically stopping the cluster.

---

# 102. Exploration vs Exploitation

Create explicit experiment intent:

```text
EXPLOITATION

EXPLORATION
```

## Exploitation

Tests improvements around attributes already associated with strong results.

## Exploration

Tests underrepresented/new visual regions.

---

# 103. Exploration Budget

Support configurable allocation conceptually:

```yaml
feedback:
  experiment-policy:
    exploitation-ratio: 0.80
    exploration-ratio: 0.20
```

Do not automatically activate this production allocation in TASK-11 unless explicitly configured and approved.

---

# 104. Novelty-Based Exploration

Use TASK-05 embeddings to identify underrepresented visual regions.

Example:

```text
Collection mostly contains:

dark blue neon wolves

Underrepresented:

warm palette
wide composition
minimal silhouette
```

Generate experiment proposal, not uncontrolled generation.

---

# 105. Experiment Candidate Ranking

There may be many hypotheses.

Create deterministic:

```text
ExperimentPriority
```

based on separate dimensions:

```text
evidence strength

expected information gain

estimated cost

sample availability

business relevance

novelty
```

Do not hide these in one unexplained AI score.

---

# 106. Priority Model

If a combined priority is needed, formula must be:

```text
versioned

documented

configurable
```

and UI should expose component scores.

---

# 107. Information Gain

Approximate:

```text
How much uncertainty could this experiment reduce?
```

Do not confuse information gain with expected revenue.

An experiment may be valuable because it teaches the system something.

---

# 108. Avoid Endless Exploitation

If system only reproduces historically successful attributes:

```text
dark
neon
wolf
centered
```

it may converge into a narrow catalog.

Feedback Engine must preserve exploration capability.

---

# 109. Avoid Trend Lock-In

Historical performance can reflect temporary trends.

Findings must contain:

```text
analysis period
```

and can become:

```text
STALE
```

after configurable time.

---

# 110. Finding Freshness

Example:

```yaml
feedback:
  finding:
    stale-after-days: 90
```

Do not use old findings indefinitely without re-analysis.

---

# 111. Temporal Stability

Check whether a finding appears across multiple windows.

Example:

```text
last 30 days

previous 30 days

previous 90 days
```

Consistent findings may receive stronger evidence classification.

---

# 112. Collection-Specific Findings

A pattern in:

```text
Cyber Wolves
```

must not automatically apply to:

```text
Minimal Landscapes
```

Support scope hierarchy:

```text
GLOBAL

PROJECT

ASSET_TYPE

COLLECTION

PLATFORM
```

---

# 113. Cross-Collection Findings

Only generate broader finding when data supports it.

Store scope explicitly.

---

# 114. Platform-Specific Findings

Example:

```text
centered_subject
```

may correlate with wallpaper downloads while:

```text
copy_space
```

may correlate with stock performance.

Keep these contexts separate.

---

# 115. Confounding Variables

At minimum expose major potential confounders.

Example:

```text
dark backgrounds perform better
```

but dark assets may also all come from:

```text
newer provider/model
```

Pattern Engine should support stratified comparisons where practical.

---

# 116. Stratified Analysis

Allow analysis within:

```text
same collection

same provider

same model

same prompt version

similar publication age
```

to reduce obvious confounding.

---

# 117. Regression Readiness

Design dataset so future models can use:

```text
linear regression

logistic regression

tree models

ranking models
```

But do not make opaque ML mandatory for TASK-11.

Explainable statistics first.

---

# 118. No Causal Claims

UI language should prefer:

```text
associated with

correlated with

observed difference

candidate hypothesis
```

Avoid:

```text
causes

guarantees

will increase
```

unless supported by controlled experiment results.

---

# 119. Experiment Result Analysis

Create:

```text
ExperimentAnalysisService
```

Input:

```text
PromptExperiment
+
TASK-10 metrics
```

Output:

```text
ExperimentResult
```

---

# 120. Experiment Result

Suggested:

```text
experimentId

variantResults

primaryMetric

secondaryMetrics

sampleSizes

effectSize

uncertainty

dataQuality

analysisStatus

calculatedAt
```

---

# 121. Variant Result

For each variant:

```text
assigned

generated

QA approved

published

observed

primary metric value

secondary metric values

cost
```

---

# 122. Statistical Comparison

For controlled experiments calculate appropriate comparisons.

Examples:

```text
difference in proportions

difference in means

difference in medians

bootstrap intervals
```

depending on metric.

Document chosen method.

---

# 123. No Automatic Winner Requirement

TASK-11 may expose:

```text
observed effect

uncertainty

sample sufficiency
```

It does not need to automatically declare a universal “winner”.

If a decision status is needed, use factual states such as:

```text
SUFFICIENT_EVIDENCE

INSUFFICIENT_EVIDENCE

INCONCLUSIVE
```

according to documented thresholds.

---

# 124. Experiment Completion

Do not complete only because:

```text
target generation count reached
```

Need both:

```text
sample/exposure requirements
+
observation window
```

where downstream performance is the primary metric.

---

# 125. QA-Only Experiments

Some experiments can complete quickly.

Example primary metric:

```text
QA_APPROVAL_RATE
```

does not require waiting 30 days for downloads.

Experiment type should determine observation requirements.

---

# 126. Learning Record

After experiment analysis create:

```text
Learning
```

Suggested fields:

```text
id

experimentId

hypothesisId

scope

summary

evidenceStatus

applicableAttributes

createdAt
```

---

# 127. Learning Is Not Prompt Mutation

A learning records what was observed.

Example:

```text
Within AMOLED Cyber Wolves, treatment using black
backgrounds showed higher 30-day download rate than
the control during experiment X.
```

It must not automatically modify all AMOLED prompts.

---

# 128. Learning Library

Create UI:

```text
Learnings
```

Filter by:

```text
collection

asset type

attribute

metric

date

experiment
```

This becomes Media Factory's accumulated knowledge base.

---

# 129. Learning Supersession

New experiment may contradict old learning.

Support:

```text
ACTIVE

SUPERSEDED

CONTRADICTED

STALE
```

Do not delete old results.

---

# 130. Contradictory Evidence

If two experiments disagree:

```text
preserve both
```

and create:

```text
CONFLICTING_EVIDENCE
```

state.

Do not let LLM arbitrarily decide which one is true.

---

# 131. Experiment Recommendation API

Possible:

```text
GET /api/v1/feedback/findings

GET /api/v1/feedback/findings/{id}

POST /api/v1/feedback/analyses

GET /api/v1/feedback/hypotheses

POST /api/v1/feedback/hypotheses/{id}/approve

POST /api/v1/feedback/hypotheses/{id}/reject

POST /api/v1/feedback/hypotheses/{id}/experiment

GET /api/v1/feedback/experiments

GET /api/v1/feedback/learnings
```

Follow existing project conventions.

---

# 132. Visual Feature API

Possible:

```text
GET /api/v1/assets/{id}/visual-features

POST /api/v1/assets/{id}/visual-features/extract

POST /api/v1/assets/{id}/visual-features/override
```

---

# 133. Analysis API

Example:

```text
POST /api/v1/feedback/analyses
```

Request:

```json
{
  "scope": {
    "collectionId": "..."
  },
  "metric": "DOWNLOAD_RATE_30D",
  "period": {
    "from": "...",
    "to": "..."
  },
  "attributes": [
    "dark_background",
    "centered_subject",
    "dark_pixel_ratio"
  ]
}
```

---

# 134. Analysis Run

Create:

```text
FeedbackAnalysisRun
```

Fields:

```text
id

scope

targetMetric

periodStart
periodEnd

featureExtractorVersion

analysisVersion

status

startedAt
completedAt

assetCount

findingCount
```

---

# 135. Reproducibility

Every finding must be reproducible.

Store:

```text
analysis version

dataset filters

attribute taxonomy version

extractor version

metric definition version

analysis parameters
```

---

# 136. Metric Definition Version

TASK-10 metrics may evolve.

Reference:

```text
MetricDefinitionVersion
```

or equivalent metadata where necessary.

Do not compare silently changed formulas as if identical.

---

# 137. Feedback Jobs

Use durable jobs for:

```text
FEATURE_EXTRACTION

DATASET_BUILD

PATTERN_ANALYSIS

SATURATION_ANALYSIS

HYPOTHESIS_GENERATION

EXPERIMENT_ANALYSIS
```

---

# 138. Idempotency

Repeated:

```text
FEATURE_EXTRACTION
```

with same:

```text
asset
extractor version
```

must not create uncontrolled duplicates.

Repeated analysis with same parameters should be identifiable.

---

# 139. Cost Tracking

Vision feature extraction and LLM hypothesis generation may cost money.

Track:

```text
VISUAL_FEATURE_EXTRACTION

HYPOTHESIS_GENERATION
```

using TASK-02/TASK-10 cost infrastructure.

---

# 140. Feedback Cost

Expose:

```text
analysis cost

feature extraction cost

experiment generation cost
```

This allows calculation of:

```text
cost of learning
```

later.

---

# 141. Cache Visual Features

Do not re-run Vision model every time analytics page opens.

Extract once per:

```text
asset + extractor version
```

and reuse.

---

# 142. Embedding Reuse

TASK-05 already computes image embeddings.

Reuse them.

Do not create duplicate CLIP embeddings solely for TASK-11.

---

# 143. Analytics Reuse

TASK-10 already computes performance metrics.

Reuse its query/aggregation layer.

Do not calculate revenue/download totals independently in Feedback Engine.

---

# 144. Data Quality Gate

Before pattern analysis verify:

```text
minimum sample

publication mapping

metric availability

currency normalization

asset lineage

feature coverage
```

Store warnings.

---

# 145. Feature Coverage

Expose:

```text
assets in scope: 1000

features extracted: 920

coverage: 92%
```

Low feature coverage must be visible.

---

# 146. Survivorship Bias

Analysis must not silently use only approved/published assets when question concerns generation quality.

Different questions require different populations.

Example:

```text
Market performance
→ published assets

QA performance
→ generated assets

Duplicate rate
→ all generated assets
```

Define population explicitly.

---

# 147. Experiment Population

Store eligibility criteria.

Example:

```text
collection = Cyber Wolves

assetType = WALLPAPER

new generations only
```

Do not retroactively move unrelated assets into an experiment.

---

# 148. Experiment Stop Conditions

Support:

```text
budget reached

sample reached

manual stop

critical QA failure

provider unavailable
```

Do not add aggressive automated “winner stopping” initially.

---

# 149. Safety Stop

If treatment causes unusually high:

```text
generation failure

QA rejection

content-policy rejection

processing failure
```

pause experiment according to configurable safety rules.

This is operational protection, not performance optimization.

---

# 150. Experiment Cost Dashboard

Show:

```text
estimated cost

actual cost

control cost

treatment cost

cost per approved asset

remaining budget
```

---

# 151. Experiment Funnel

For each variant:

```text
Assigned
↓
Generated
↓
QA Approved
↓
Similarity Accepted
↓
Processed
↓
Published
↓
Observed
```

This reveals where treatment differences originate.

---

# 152. Experiment Timeline

Show:

```text
Created

Approved

Generation started

Generation completed

Publication completed

Observation window

Analysis ready

Completed
```

---

# 153. Experiment Result UI

Show side-by-side:

```text
CONTROL

TREATMENT
```

with:

```text
sample

QA approval

downloads

download rate

revenue

cost

profit

effect size

uncertainty
```

---

# 154. Visual Comparison

Show representative assets from each variant.

Humans should be able to visually understand what actually changed.

---

# 155. Prompt Diff

For prompt experiments display:

```text
Prompt A
vs
Prompt B
```

using TASK-03 diff infrastructure.

Highlight changed:

```text
variables

presets

positive prompt

negative prompt
```

---

# 156. Provider Experiment

Support hypothesis such as:

```text
Provider B may provide similar downstream performance
at lower generation cost.
```

Control:

```text
Provider A
```

Treatment:

```text
Provider B
```

Keep prompt/other parameters as equivalent as provider capabilities permit.

---

# 157. Processing Experiment

Possible:

```text
Processing Profile A
vs
Processing Profile B
```

Example:

```text
sharpen strength

compression quality

crop policy
```

Only if TASK-06 lineage supports immutable versions.

---

# 158. Similarity Threshold Experiments

Future-compatible experiment:

```text
near-duplicate threshold A
vs
threshold B
```

Do not implement if it would contaminate shared production safety rules.

Architecture should allow controlled offline evaluation first.

---

# 159. Offline Experiments

Support:

```text
OFFLINE
```

experiments for things that do not need paid generation/publication.

Examples:

```text
similarity thresholds

feature extraction versions

QA thresholds
```

---

# 160. Online Experiments

Use:

```text
ONLINE
```

for experiments requiring:

```text
generation

publication

real user performance
```

Keep distinction explicit.

---

# 161. Experiment Provenance

Every experiment should reference:

```text
finding

hypothesis

analysis run

dataset scope

prompt versions

provider configs

processing profiles
```

Complete lineage:

```text
Analytics
↓
Finding
↓
Hypothesis
↓
Experiment
↓
Generation
↓
Performance
↓
Learning
```

---

# 162. Feedback Lineage UI

Allow navigating:

```text
Learning
→ Experiment
→ Hypothesis
→ Finding
→ Analysis
→ Assets
```

and reverse direction.

---

# 163. Database

Create/refine migrations for:

```text
visual_attribute_definition

asset_visual_feature

visual_feature_override

feedback_analysis_run

feedback_finding

feedback_finding_evidence

experiment_hypothesis

learning
```

Extend existing experiment tables only where required.

Do not duplicate TASK-03 entities.

---

# 164. Important Indexes

Consider:

```text
asset_visual_feature.asset_id

asset_visual_feature.attribute_definition_id

asset_visual_feature.extractor_version

feedback_finding.analysis_run_id

feedback_finding.attribute_key

feedback_finding.target_metric

experiment_hypothesis.finding_id

learning.experiment_id
```

---

# 165. JSONB

Use JSONB only for genuinely flexible structures such as:

```text
analysis parameters

structured feature value

scope definition
```

Do not hide core searchable dimensions inside giant JSON blobs.

---

# 166. Unit Tests — Feature Extraction

Test:

```text
deterministic features

Vision structured response

invalid Vision JSON

missing attribute

confidence

extractor version

manual override
```

---

# 167. Unit Tests — Pattern Analysis

Create deterministic fixture dataset.

Verify:

```text
sample counts

means

medians

quantiles

relative differences

effect sizes

confidence intervals
```

Use known expected numbers.

---

# 168. Unit Tests — Minimum Samples

Example:

```text
feature group = 3 assets
minimum = 20
```

Verify:

```text
INSUFFICIENT_DATA
```

---

# 169. Unit Tests — Age Normalization

Verify asset ages do not accidentally mix:

```text
lifetime
```

with:

```text
first 30 days
```

metrics.

---

# 170. Unit Tests — Requested vs Observed

Create:

```text
requested:
centered = true

observed:
centered = false
```

Verify compliance analytics.

---

# 171. Unit Tests — Saturation

Create synthetic cluster:

```text
first 10 assets:
high performance

next 20:
medium

last 30:
low
```

Verify saturation analysis detects descriptive declining marginal performance according to configured rules.

---

# 172. Unit Tests — Hypothesis Generation

Mock LLM.

Verify calculated evidence is passed correctly.

LLM must not modify:

```text
sample size

metric values

effect size

confidence intervals
```

---

# 173. Unit Tests — Experiment Proposal

Verify:

```text
finding
↓
hypothesis
↓
experiment draft
```

contains:

```text
control

treatment

primary metric

sample

budget

observation window
```

---

# 174. Unit Tests — Prompt Immutability

Feedback-generated experiment must not mutate an existing published PromptVersion.

Verify new version is created.

---

# 175. Unit Tests — Budget

Simulate experiment reaching:

```text
maxBudget
```

Verify further paid generations are stopped/paused.

---

# 176. Unit Tests — Human Approval

Verify:

```text
PROPOSED
```

experiment cannot begin generation before approval under default configuration.

---

# 177. Unit Tests — Experiment Attribution

Verify every generated asset retains:

```text
experimentId

variantId
```

through publication and analytics.

---

# 178. Unit Tests — Result Analysis

Create known A/B fixture.

Verify:

```text
variant sample sizes

primary metric

effect

uncertainty

cost
```

are calculated correctly.

---

# 179. Unit Tests — Contradictory Learnings

Create:

```text
Experiment 1 → positive effect

Experiment 2 → negative effect
```

Verify old learning is preserved and conflict can be represented.

---

# 180. Integration Test

Test complete loop:

```text
100 historical assets
↓
Visual Feature Extraction
↓
TASK-10 Performance
↓
Pattern Analysis
↓
Finding
↓
Hypothesis
↓
Human Approval
↓
PromptExperiment
↓
Mock Generations
↓
QA
↓
Processing
↓
Mock Publication Metrics
↓
Experiment Analysis
↓
Learning
```

---

# 181. No Paid APIs in CI

Use:

```text
Mock Vision Provider

Mock LLM

Mock Image Provider

Mock Analytics
```

No real paid API.

---

# 182. Statistical Fixtures

Create synthetic datasets where expected relationships are known.

Examples:

```text
NO_EFFECT

STRONG_POSITIVE_EFFECT

STRONG_NEGATIVE_EFFECT

SMALL_SAMPLE

HIGH_VARIANCE

OUTLIER_HEAVY

CONFOUNDED
```

---

# 183. Confounding Fixture

Example:

```text
all dark images
→ Provider A

all bright images
→ Provider B
```

Verify system exposes provider imbalance and does not present naive conclusion without warning.

---

# 184. Frontend Tests

Test:

```text
finding filters

attribute explorer

visual examples

hypothesis approval

experiment approval

budget display

experiment funnel

result comparison

learning history

data-quality warnings
```

---

# 185. Performance

Representative scale:

```text
100,000 assets

millions of visual features

millions of analytics records
```

Do not load complete dataset into JVM memory for every analysis.

Use:

```text
SQL aggregation

batch processing

streaming

temporary analytical tables
```

where appropriate.

---

# 186. Python Analytics Worker

If advanced statistics become awkward in Java, architecture may introduce:

```text
workers/analytics/
```

Python worker.

However:

```text
Spring Boot
```

remains orchestration/source-of-truth layer.

Do not split simple calculations into another service unnecessarily.

---

# 187. Python Boundary

If used:

```text
Backend
↓
Analysis Job
↓
Python Analytics Worker
↓
Structured Analysis Result
↓
Backend Persistence
```

Use versioned schemas.

---

# 188. Reproducible Analysis

Persist:

```text
analysis code/version

dataset parameters

feature versions

metric versions

random seed
```

where randomness/bootstrap is used.

---

# 189. Random Seed

Bootstrap/statistical sampling should support deterministic:

```text
randomSeed
```

for reproducibility.

---

# 190. Documentation

Create:

```text
docs/feedback-engine.md

docs/visual-attributes.md

docs/feedback-analysis.md

docs/feedback-statistics.md

docs/hypothesis-engine.md

docs/experiment-proposals.md

docs/experiment-analysis.md

docs/learnings.md

docs/saturation-analysis.md
```

---

# 191. Visual Attribute Documentation

Document:

```text
taxonomy

definitions

value types

deterministic vs semantic features

requested vs observed

confidence

versioning

human override
```

---

# 192. Statistical Documentation

Document every implemented method:

```text
mean

median

quantiles

effect size

confidence interval

correlation

bootstrap

multiple-comparison handling
```

Include assumptions and limitations.

---

# 193. Experiment Documentation

Document:

```text
control

treatment

primary metric

secondary metrics

sample size

observation window

budget

assignment

completion

analysis
```

---

# 194. Definition of Done

TASK-11 is complete when this full loop works:

```text
Historical Assets
↓
Visual Feature Extraction
↓
TASK-10 Performance Metrics
↓
Pattern Analysis
↓
Feedback Finding
↓
Hypothesis
↓
Experiment Proposal
↓
Human Approval
↓
TASK-03 Prompt Experiment
↓
New Generations
↓
QA
↓
Similarity
↓
Processing
↓
Publication
↓
Analytics
↓
Experiment Result
↓
Learning
```

Example scenario:

```text
Cyber Wolves Collection

200 historical assets
↓
analysis detects:

dark background
+
centered subject

is associated with higher 30D download rate
↓
Finding created
↓
Hypothesis created
↓
Experiment proposed:

A:
current prompt

B:
dark background + centered subject
↓
estimated cost displayed
↓
user approves
↓
50 A + 50 B
↓
generation
↓
publication
↓
30D analytics
↓
controlled comparison
↓
Learning stored
```

---

# 195. Verification

Before completing TASK-11:

1. run backend tests;
2. run frontend tests;
3. run integration tests;
4. run production builds;
5. apply migrations;
6. create visual attribute taxonomy;
7. extract deterministic visual features;
8. extract mocked Vision features;
9. verify feature provenance;
10. verify extractor versioning;
11. manually override one feature;
12. re-run extraction;
13. verify human override remains authoritative;
14. build feedback dataset;
15. verify TASK-10 metrics are reused;
16. verify TASK-05 embeddings are reused;
17. analyze boolean attribute;
18. analyze enum attribute;
19. analyze numeric attribute;
20. calculate mean;
21. calculate median;
22. calculate quantiles;
23. calculate effect size;
24. calculate uncertainty;
25. verify minimum sample guard;
26. verify insufficient-data state;
27. verify age-normalized metric;
28. analyze requested vs observed attributes;
29. analyze prompt compliance by provider;
30. analyze prompt variable;
31. analyze prompt preset;
32. analyze PromptVersion;
33. analyze provider;
34. analyze model;
35. analyze QA rejection;
36. analyze similarity cluster;
37. run saturation analysis;
38. calculate collection diversity;
39. create FeedbackFinding;
40. attach representative evidence assets;
41. generate hypothesis using mock LLM;
42. verify numeric evidence cannot be changed by LLM;
43. create experiment proposal;
44. verify immutable control definition;
45. verify immutable treatment definition;
46. calculate estimated cost;
47. set max budget;
48. attempt execution before approval;
49. verify blocked;
50. approve experiment;
51. create TASK-03 PromptExperiment;
52. assign variants deterministically;
53. generate mock assets;
54. verify experiment attribution;
55. run QA;
56. run similarity filtering;
57. process assets;
58. publish mock assets;
59. ingest analytics;
60. verify variant exposure;
61. wait/simulate observation window;
62. analyze experiment;
63. calculate primary metric;
64. calculate secondary metrics;
65. calculate effect;
66. calculate uncertainty;
67. verify sample sufficiency;
68. create Learning;
69. navigate Learning → Experiment → Finding;
70. create contradictory experiment result;
71. verify conflict preserved;
72. verify stale finding handling;
73. test exploration hypothesis;
74. test exploitation hypothesis;
75. test saturation hypothesis;
76. verify budget guard;
77. verify safety stop;
78. verify analysis reproducibility;
79. rebuild analysis using same seed;
80. verify equivalent results;
81. verify no paid APIs called in CI.

---

# 196. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Analytics
→ Visual Features
→ Pattern Analysis
→ Findings
→ Hypotheses
→ Experiments
→ Results
→ Learnings
```

## Visual Features

Report:

```text
attribute taxonomy

deterministic extractors

Vision extractors

feature provenance

extractor versioning

human override
```

## Analysis

Report implemented:

```text
sample statistics

effect calculations

uncertainty

correlation

combination analysis

numeric features

saturation

diversity
```

## Feedback Findings

Report:

```text
finding model

scope

confidence

evidence

data-quality guards

staleness
```

## Hypothesis Engine

Report:

```text
LLM role

structured output

guardrails

cost tracking
```

## Experiment Engine Integration

Report integration with TASK-03:

```text
PromptExperiment

variants

deterministic assignment

prompt versions

budget

human approval
```

## Experiment Analysis

Report:

```text
primary metric

secondary metrics

observation window

effect

uncertainty

sample sufficiency
```

## Learnings

Report:

```text
learning persistence

scope

supersession

contradictory evidence

staleness
```

## Frontend

Report implemented:

```text
Feedback Overview

Visual Attribute Explorer

Findings

Hypotheses

Experiment Proposals

Experiments

Saturation

Learnings
```

## Tests

Report:

```text
feature extraction

statistics

saturation

hypothesis

experiment proposal

budget

human approval

experiment attribution

result analysis

integration

frontend
```

## Performance

Report:

```text
dataset size tested

analysis duration

memory usage

database query performance
```

## Remaining Limitations

Explicitly document:

```text
correlation vs causation

Vision feature accuracy

sample-size limitations

platform analytics limitations

confounding

selection bias

survivorship bias

historical trend drift

statistical limitations

LLM hypothesis limitations
```

Never claim causal knowledge from observational correlations.

---

# Engineering Principles

Throughout TASK-11:

1. Facts, findings, hypotheses, experiments and learnings are different concepts.
2. Never turn correlation directly into production behavior.
3. Historical performance does not prove causality.
4. Controlled experiments are the preferred mechanism for validating hypotheses.
5. Human approval is required before Feedback Engine starts paid experiments by default.
6. Never silently mutate production prompts.
7. Published PromptVersions remain immutable.
8. Experiment control and treatment must reference immutable versions.
9. Every experiment must define a primary metric before execution.
10. Do not redefine the primary metric after results arrive.
11. Every downstream-performance experiment must define an observation window.
12. Compare assets using comparable exposure windows.
13. Do not compare lifetime metrics across assets of radically different ages without normalization.
14. Minimum sample-size guards are mandatory.
15. Small samples must be clearly marked.
16. Use deterministic/statistical code for numerical analysis.
17. LLMs may explain calculated evidence but must not invent statistics.
18. Vision-extracted attributes are probabilistic observations, not ground truth.
19. Every visual feature must preserve provenance.
20. Human feature overrides must be auditable.
21. Human corrections must not be silently overwritten.
22. Requested visual attributes and observed visual attributes are separate.
23. Reuse TASK-05 embeddings.
24. Reuse TASK-10 analytics.
25. Reuse TASK-03 experiments.
26. Do not create duplicate analytics, embedding or experiment architectures.
27. Use deterministic CV for measurable visual properties.
28. Use Vision models for semantic visual properties.
29. Cache visual extraction by asset + extractor version.
30. Version visual taxonomy and extraction logic.
31. Findings must include their scope.
32. Collection-specific evidence must not automatically become global knowledge.
33. Platform-specific findings remain platform-specific unless broader evidence exists.
34. Asset population must be explicit for every analysis.
35. Market-performance analysis generally uses published assets.
36. QA analysis must include generated/rejected assets where appropriate.
37. Rejected generations remain economically relevant.
38. Analyze median/quantiles, not only mean.
39. Protect against outlier-driven conclusions.
40. Consider multiple-comparison risk when scanning many features.
41. Expose potential confounding.
42. Prefer stratified comparisons when practical.
43. Do not hide analytical uncertainty.
44. Do not collapse performance into one unexplained magic score.
45. Provider quality, cost, QA and revenue are separate dimensions.
46. Novelty is not automatically quality.
47. Diversity is not automatically quality.
48. Similarity is not automatically bad.
49. Saturation is a hypothesis signal, not an automatic delete/stop command.
50. Preserve exploration so the factory does not converge onto one narrow visual style.
51. Preserve exploitation so known useful patterns can be tested further.
52. Exploration/exploitation allocation must be configurable.
53. Feedback findings become stale over time.
54. Preserve contradictory experimental evidence.
55. Never delete inconvenient old learnings.
56. Experiment assignment must remain immutable.
57. Experiment generation must use normal production pipelines.
58. Do not create special low-quality shortcuts for experiment assets.
59. Track QA/similarity/publication funnel separately for every variant.
60. Budget limits are mandatory for paid experiments.
61. Estimated and actual experiment costs must remain distinguishable.
62. A provider/API failure is not evidence against the experiment hypothesis.
63. Operational failures must be separated from market results.
64. Safety stops may pause experiments for abnormal failure/rejection rates.
65. Avoid aggressive early “winner” stopping in the initial implementation.
66. Experiment results must include sample size and uncertainty.
67. Learnings must reference their experiment and evidence.
68. A learning must not directly rewrite future prompts.
69. Any adoption of a learning into production should create explicit versioned configuration.
70. Analysis runs must be reproducible.
71. Randomized/bootstrap analysis must persist a seed.
72. All expensive Feedback Engine operations must be asynchronous.
73. Analysis jobs must survive restart.
74. CI must never call paid AI providers.
75. Data-quality warnings must remain visible.
76. Every recommendation/proposal should be traceable:
    `proposal → hypothesis → finding → analysis → assets → metrics`.
77. Every learning should be traceable:
    `learning → experiment → variants → generations → assets → publications → analytics`.
78. TASK-11 measures and learns; it does not become an uncontrolled autonomous agent.
79. Architecture must support future predictive models without making them mandatory now.
80. The long-term goal is a controlled learning loop:

```text
CREATE
↓
MEASURE
↓
UNDERSTAND
↓
HYPOTHESIZE
↓
EXPERIMENT
↓
LEARN
↓
CREATE BETTER NEXT EXPERIMENT
↺
```

The result of TASK-11 should transform Media Factory from an automated content-production pipeline into a **data-driven experimentation system** that can discover which visual characteristics are associated with performance, understand where production budget is being wasted, detect saturation and unexplored visual space, formulate testable hypotheses, create controlled experiments, measure their outcomes, and accumulate a versioned knowledge base — without sacrificing reproducibility, human control or production safety.
