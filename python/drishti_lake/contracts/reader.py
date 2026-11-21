# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The data contract of one table kind, read from its YAML file (``<kind>.contract.yaml``; the format: ``schema.json`` beside this
file), and the profile a host adds to it: its own keys (one host's: ``mnemonic``, ``pack``, ``class``, ``binding-hint``, ``list``), which
the reader accepts and hands back untouched. Java: ``com.ash.lake.contract.Contract`` (the same reading)."""
from __future__ import annotations

import copy
import json
from dataclasses import dataclass, field
from pathlib import Path

import yaml

SCHEMA = Path(__file__).with_name("schema.json")
_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


@dataclass(frozen=True)
class Profile:
    """A host's own contract keys: their JSON-schema definitions (a key here replaces the core's definition of the same name, as a host's
    stricter ``replaced-by``), the ones every contract of the host must have, and its schema's title. No profile: the core schema."""
    properties: dict = field(default_factory=dict)
    required: tuple = ()
    title: str | None = None

    def schema(self) -> dict:
        """The core schema with the profile's keys."""
        s = copy.deepcopy(core_schema())
        s["properties"].update(copy.deepcopy(self.properties))
        s["required"] = list(s["required"]) + [k for k in self.required if k not in s["required"]]
        if self.title:
            s["title"] = self.title
        return s

    def host_keys(self) -> tuple:
        return tuple(self.properties)


CORE = Profile()


def core_schema() -> dict:
    return json.loads(SCHEMA.read_text(encoding="utf-8"))


def load(path) -> dict:
    """A contract file as it is written (a mapping; an empty file: {}); every key kept, the host's too."""
    doc = yaml.load(Path(path).read_text(encoding="utf-8"), Loader=_LOADER)
    if doc is None:
        return {}
    if not isinstance(doc, dict):
        raise ValueError(f"{path}: a data contract is a mapping (kind:, version:, fields:, ...)")
    return doc


class Contract:
    """One kind's contract: its fields (type, presence, enum, pattern), the nested records' fields, the required list (the validation
    rule ``required`` when there is one, else the fields marked required), the derived fields (never loaded), the links, and the host's
    keys (``host``) untouched."""

    def __init__(self, doc: dict, path: Path | None = None, profile: Profile = CORE):
        self.path = Path(path) if path else None
        self.doc = doc
        self.kind = str(doc.get("kind") or "")
        self.version = str(doc.get("version") or "")
        self.dated_by = str(doc.get("dated-by") or "")
        self.fields = {f["name"]: f for f in doc.get("fields") or []}
        rules = doc.get("validation") or []
        req = [r for r in rules if r.get("rule") == "required"]
        self.required = list(req[0]["fields"]) if req else [n for n, f in self.fields.items() if f.get("presence") == "required"]
        self.enums = {r["field"]: r["values"] for r in rules if r.get("rule") == "enum"}
        self.patterns = {r["field"]: r["pattern"] for r in rules if r.get("rule") == "pattern"}
        for n, f in self.fields.items():
            if f.get("enum"):
                self.enums.setdefault(n, f["enum"])
            if f.get("pattern"):
                self.patterns.setdefault(n, f["pattern"])
        self.derived = {n for n, f in self.fields.items() if f.get("presence") == "derived"}
        self.nested = {p: {f["name"]: f for f in fs} for p, fs in (doc.get("nested") or {}).items()}
        self.links = {n: f["link"] for n, f in self.fields.items() if f.get("link")}
        for p, fs in self.nested.items():
            self.links.update({f"{p}.{n}": f["link"] for n, f in fs.items() if f.get("link")})
        self.links.update({str(x["field"]): str(x["kind"]) for x in doc.get("links") or [] if x.get("field") and x.get("kind")})
        self.host = {k: doc[k] for k in profile.host_keys() if k in doc}

    @classmethod
    def read(cls, path, profile: Profile = CORE) -> "Contract":
        return cls(load(path), path, profile)

    def key(self) -> list[str]:
        k = self.doc.get("key")
        return [str(x) for x in k] if isinstance(k, list) else ([str(k)] if k else [])

    def single_key(self) -> str | None:
        """The one field that identifies a row, None when the key has several fields or none (Java: Contract.singleKey)."""
        k = [x.strip() for x in self.key() if x and x.strip()]
        return k[0] if len(k) == 1 and "," not in k[0] else None

    def field(self, dotted: str) -> dict | None:
        """A field by its dotted name: ``NAME``, ``KYC.STATUS``, ``SENSITIVITIES[].VALUE``; None when the contract has no such field."""
        if "." not in dotted:
            return self.fields.get(dotted)
        path, name = dotted.rsplit(".", 1)
        return (self.nested.get(path) or {}).get(name)

    def required_in(self, path: str) -> list[str]:
        """The required fields of a nested path (``KYC``, ``SENSITIVITIES[]``)."""
        return [n for n, f in (self.nested.get(path) or {}).items() if f.get("presence") == "required"]
