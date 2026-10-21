# L1 config: authoring, testing and publishing

How a change to L1 config gets from a pull request to a running worker. Design background is in
[`l1-lld.md`](l1-lld.md), section 12.

## 1. Where things live

```text
src/main/resources/
  l1-config/                         the config bundle (one per repo for the POC)
    resources/   axis-precheck.json  core.json  lendercore-mongo.json
    operations/  common/*.json  axis/*.json
    recipes/     axis/axis.precheck.dedupe.json
    tests/       axis/axis.precheck.dedupe/*.golden.json
  l1-schemas/                        JSON Schemas (ship with the code that reads the config)
    recipe.schema.json  operation.schema.json  resource.schema.json  golden.schema.json
```

To get completion and inline errors in your editor, map the schemas to the folders. In IntelliJ, use
*Settings → JSON Schema Mappings*, and map `l1-config/recipes/**` to `recipe.schema.json`, and so on. Do not add
a `"$schema"` key to config files: the model rejects unknown fields.

## 2. Versions

Recipes and operations are **immutable once published**. Resources are live config and are not versioned.

| Rule | Why |
|---|---|
| Every recipe and operation file has a `version` (missing = 1) | The number in git is the number in the registry, so a recipe's `op@N` pins are visible in review |
| To change a published document, raise its `version` | A published `ref@N` never changes; open cases pinned to it keep running exactly what they started with |
| A new version must be higher than the latest published one (gaps are allowed) | Versions never go backwards. QA may have seen versions that prod skips |
| Re-publishing the same content is a no-op | Formatting, key order and whitespace don't count; the hash is over the parsed document |
| A recipe pins operations explicitly (`axis.precheck.invoke@2`) | Changing an operation never silently changes a recipe |

**Changing an operation:** raise the operation's `version`, point the recipes that should use it at the new
`@N`, and raise those recipes' `version` too.

- The old operation file can be deleted once no recipe in git pins it; the registry keeps the old version.
- To keep both versions in git, keep two files, e.g. `axis.precheck.invoke.json` and `axis.precheck.invoke.v2.json`.

**Changing a resource** (URL, timeouts, values): edit it in place. Workers pick it up within
`l1.registry.resource-ttl` (60 s).

## 3. Golden tests

Every recipe's highest version in the bundle needs at least one golden test under `tests/`. A golden test runs the
real engine, but swaps the executors:

| Kind | In a golden test |
|---|---|
| `HTTP` | Must be mocked: `{"resp": {"status", "headers", "body"}}` or `{"transport": {"sent", "timeout"}}` |
| `MAP`, `GEN` | Run for real (templates, `nameSplit`, stable ids); `sys.now` is fixed (`now`, default 2026-01-01T00:00:00Z) |
| `MONGO` | Mock with `{"result": ...}`, or leave unmocked: reads find nothing, writes report one matched document |

```json
{
  "name": "fresh customer, dedupe accepted",
  "recipe": "axis.precheck.dedupe",
  "input": { "loanRequestId": "LR-GOLDEN-1", "runNo": 1 },
  "mocks": {
    "core.fetchCustomerByLoanRequest@1": { "resp": { "status": 200, "body": { "loandata": [ { "customer": { "...": "..." } } ] } } },
    "axis.precheck.invoke@1": { "resp": { "status": 200, "body": { "data": { "applicantId": "APP-TEST-9" } } } }
  },
  "expect": {
    "outcome": "INITIATED",
    "out": { "applicantId": "APP-TEST-9" },
    "calls": { "axis.precheck.invoke@1": { "params.body.first_name": "RAVI", "params.body.address[0].address_city": "Bengaluru" } },
    "notCalled": ["precheck.markDedupeDone@1"]
  }
}
```

- `expect` holds exactly one of `outcome` or `failure` (`L1_TECHNICAL`, `L1_BUSINESS`, `L1_UNKNOWN`, `L1_PERMANENT`).
- `out` lists keys whose values must match.
- `calls` paths are checked against the first call to that operation.
- `recipe` without `@N` means the highest version in the bundle.
- Use masked or synthetic data only, never real customer data.

## 4. Commands

The CLI is `com.rupeek.l1.publish.cli.L1ConfigCli`, run through Maven. Locally, `dev/l1-local/l1-local.sh config …`
wraps it.

```bash
# Offline: schema, references, CEL, dataflow, golden tests. No registry needed.
mvn -q compile exec:java -Dexec.args="validate --env qa"

# Against a registry: also version checks (immutability, forward-only)
L1_CONFIG_MONGO_URI=mongodb://… mvn -q compile exec:java -Dexec.args="validate --env qa"

# Publish (CI does this). --dry-run validates against the registry and writes nothing.
L1_CONFIG_MONGO_URI=mongodb://… mvn -q compile exec:java -Dexec.args="publish --env qa --reason 'why'"

# What is live
mvn -q compile exec:java -Dexec.args="status"

# Emergency: move cases pinned to a bad version onto another one (newest redirect wins; --to = --from undoes it)
mvn -q compile exec:java -Dexec.args="redirect --ref axis.precheck.dedupe --from 3 --to 2 --reason 'v3 sends wrong DOB'"
```

### Parity against the legacy system

Compares the request a recipe would send with what lendercore logged in `LenderApiAuditLog`:
- the lender call is mocked, so it is never called;
- `--live` operations, such as the Core customer fetch, run for real;
- nothing is written anywhere;
- values in the report are masked unless you pass `--show-values`.

```bash
CORE_TOKEN=… mvn -q compile exec:java -Dexec.args="parity --env qa \
  --audit-uri mongodb://<qa-lendercore> --recipe axis.precheck.dedupe --capture axis.precheck.invoke \
  --live core.fetchCustomerByLoanRequest --request-type PRECHECK_DEDUPE --lender axis \
  --ignore client_loan_id --limit 50"
```

Customer data is read from Core today. A customer who changed (for example) their phone since the logged call
shows up as a diff on that field. Look for fields that differ across many cases.

| Option | Default |
|---|---|
| `--dir` | `src/main/resources/l1-config` |
| `--env` | `local` for validate; required for publish |
| `--mongo-uri` | `L1_CONFIG_MONGO_URI` |
| `--database` | `lih_config` |
| `--by` | `GITHUB_ACTOR`, else `USER` |
| `--reason` | `L1_PUBLISH_REASON` |

Exit codes: `0` ok, `1` validation or publish failed, `2` usage error.

## 5. What `publish` checks, in order

1. Each file is valid JSON (duplicate keys rejected) and matches its schema.
2. Versions against the registry: an existing `ref@N` must have identical content; a new one must be higher than
   the latest.
3. Every HTTP resource has a block for the target environment, with `baseUrl` or `baseUrlRef`.
4. Everything is compiled with the worker's own compiler: `op@N` references (from the bundle, or already in the
   registry), resources, every CEL expression in its scope, dataflow (`steps.x` read only after `x` ran), declared
   outcomes and outputs, and kind checks (HTTP method and path, Mongo operators and collection allowlist).
5. Golden tests. Every recipe needs at least one.
6. Only if all of the above pass: new operation versions are inserted, then recipe versions, then the `latest`
   pointers move, then changed resources are written.
7. Every real publish attempt, passed or failed, is appended to `l1_publish_log`.

## 6. CI

`.github/workflows/l1-config.yml`:

| Trigger | Job | Does |
|---|---|---|
| Pull request touching config, schemas, L1 code or `pom.xml` | `validate` | `mvn test`, then `validate --env qa` against the QA registry (offline if the secret is missing) |
| Merge to `master` | `publish-qa` | `publish --env qa`, reason = commit message |
| Manual (*Run workflow*, with a reason) | `publish-prod` | `publish --env prod` in the `l1-prod` environment |

To set it up:
- add the repository secrets `L1_CONFIG_MONGO_URI_QA` and `L1_CONFIG_MONGO_URI_PROD`;
- give the `l1-prod` environment required reviewers, which is the prod approval.

## 7. Workers

| Property | Meaning |
|---|---|
| `l1.registry.mode` | `classpath` (default: load `l1.config-location`, for dev and tests) or `mongo` (published versions) |
| `l1.registry.uri` | Registry connection string; empty = reuse the application's MongoClient |
| `l1.registry.database` | `lih_config` |
| `l1.registry.resource-ttl` | `60s` |

In `mongo` mode, the worker works like this:
- At start-up it compiles every stored recipe version and refuses to start if one no longer compiles.
- A version published later is loaded on first use, with no restart.
- `latest` pointers and redirects are read on every call.

## 8. Not done yet

- **Retiring old versions:** every stored version is compiled at start-up. Retiring one needs a check that no open
  case pins it (a Temporal visibility query).
- **Redirects** apply to recipes only. Operations are pinned inside immutable recipes, so they cannot be redirected.
- **Recording the `rk.*` library version:** it is not stored with each version. Start-up compilation catches a
  removed function instead.
