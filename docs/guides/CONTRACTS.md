<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Data contracts

A **data contract** is the shape of what may be loaded into one kind's table: its fields, their types and presence, the key, the date field,
the cadence and the validation rules. The loader checks every row against the contract **before the first write** (`target.contract`), so a
malformed row is rejected with a reason and never reaches a table. One YAML file per kind, `<kind>.contract.yaml`; one reader and one set of
checks in Java (`lake-contracts`, `com.ash.lake.contract`: `Contract`, `ContractChecker`) and in Python (`drishti_lake.contracts`), which say
the same words (`tck/fixtures/contracts/records.json` is their shared cases). The contract files of a product belong to that product; this
repository holds the schema, the readers, the checkers and the page generator.

## The format

The schema is `python/drishti_lake/contracts/schema.json` (JSON Schema, the one home of every key). Required: `kind`, `version` (`1.2`), `status`
(`active`, `retiring`, `planned`), `title`, `description`, `source`, `grain`, `key` (field names), `dated-by` (a field, `snapshot` or `on request`),
`cadence` (`frequency`: `daily`, `intraday`, `static`, `on change`; `due`), `fields`, `validation`, `review`. Optional: `retiring-in`, `replaced-by`,
`id-pattern`, `nested` (the fields of a record or record list, keyed by path such as `KYC` or `LEGS[]`), `links` (a field to another kind), `notes`.

A field has a `name`, a `type` (`text`, `id`, `integer`, `amount`, `rate`, `date`, `timestamp`, `boolean`, `vector`, `record`, `record list`, `text list`, `id list`,
`date list`, `list`, `unknown`) and a `presence` (`required`, `optional`, `derived`: computed, never loaded), and may carry `unit`, `description`, `example`,
`enum`, `pattern`, `note`. A validation rule is one of `required`, `unique`, `enum`, `pattern`, `link`, `range`, `vector-length`, `type`.

**A host's own keys** (an owner, a binding hint) are declared as a *profile* (`{"properties": {...}, "required": [...]}`): the core schema refuses an
unknown key, a profile accepts the host's, and the readers hand them back untouched (`Contract.host`, `Contract.node()`).

A sample, with its generated page: [tck/fixtures/contracts/desk/contracts/position.contract.yaml](../../tck/fixtures/contracts/desk/contracts/position.contract.yaml) and
[contracts/desk.md](../contracts/desk.md).

## Commands

```text
python -m drishti_lake contracts check FILE... [--profile PROFILE.json]       every file against the schema (with the host's keys when given)
python -m drishti_lake contracts pages FILE... --out DIR [--check]            one page per folder of contracts, and an index; --check writes nothing and fails when a page is stale
```

The pages in [docs/contracts/](../contracts/README.md) are **generated** from the sample contracts by the second command and checked with
`--check`; edit the contracts, never the pages. A product regenerates its own pages from its own contract files, with its words through a
`PageProfile`.

## Use in a load

```yaml
target:
  format: delta
  domain: desk
  kind: position
  contract: /abs/path/to/position.contract.yaml
```

With the sample contract and three rows (one good, one `DESK: D9` outside its values, one `POSITION_ID: X-3` outside its pattern), the load commits the
good row and rejects the others by reason (the summary's `rejects`, exit code 2):

```text
the contract of position (version 1.2): DESK: not one of the contract's values                       1
the contract of position (version 1.2): POSITION_ID: does not match ^P-[0-9]+$                        1
```

The check order is part of the rule: JSON, an object, the id, the date, the vectors, then the document against the contract; a row with several
faults is rejected for the first ("; and N more" counts the rest). The words name the field and the rule, never the value. In code (Java):
`ContractChecker.standard().problems(Contract.read(path), recordNode)` returns the problem list and `ContractChecker.reason(contract, problems)` the reject
text; in Python `contracts.Contract.read(path)` then `contracts.record(contract, row)` and `contracts.reason(contract, problems)`. `contracts.drift`
compares a contract with the documents a table holds and with a host's link graph (id patterns, links, fields, required, enums, types).
