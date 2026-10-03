# Asset economics

All backend money arithmetic uses PostgreSQL NUMERIC and Java BigDecimal. Raw metrics/rates/allocations support 12 fractional digits; existing generation charges retain their eight-digit storage precision. Ratios use decimal division. The UI formats money for display; chart coordinates are not financial calculations.

| Metric | Formula |
| --- | --- |
| Profit | revenue − cost |
| ROI | (revenue − cost) / cost |
| Cost per download | cost / downloads |
| Revenue per download | revenue / downloads |
| Revenue per view | revenue / views |
| Like rate | likes / views |
| Download rate | downloads / views |
| Approval rate | approved generations / generations |
| Cost per approved | all attributed operation cost / approved generations |
| Cost per published | cost / distinct published assets |
| Cost per revenue unit | cost / revenue |
| Amount to break even | max(cost − revenue, 0) |

Unavailable numerator or zero/unavailable denominator returns NULL, not zero or infinity. Observed zero revenue is distinct from missing revenue. An operation with unknown price makes the affected cost total unknown, rather than presenting a known subtotal as full cost. Missing exchange rates similarly invalidate the affected monetary total and increment `missing_money_facts`.

Cost includes rejected and failed generations without assets. Descendant variants do not inherit another copy of the master's generation cost. Current QA status determines approved/rejected cohorts; occurrence dates determine period cost and revenue. Ratios in a narrow period therefore describe that period's activity, not a fully matched lifetime acquisition cohort.

## Currency

Default report currency is USD; query `currency` selects another ISO currency. `POST /currency-rates` takes `{currency, baseCurrency, date, rate, source, createdBy}`. Rates are explicit daily historical rates, not a live feed. A rate applies on the fact's UTC occurrence date; same-currency conversion is one. There is no silent latest-rate fallback or automatic triangulation.

Original amounts and currencies remain unchanged. Asset details expose original amount, converted amount, rate, rate date and source. A previously missing rate can complete a report later; existing rate rows cannot be silently rewritten. There is no accounting-period close or automated FX correction workflow in this release.

## Interpretation

Counts and financial amounts are descriptive. They do not establish causal superiority of a provider, prompt or style. No hidden performance score, forecast, automatic winner or generation strategy change is implemented.
