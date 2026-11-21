#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Writes the contract fixtures' expected answers from the Python path: records.json (each record and its problems under
desk/contracts/position.contract.yaml; the Java checker must give the same words: ContractCheckerTest) and pages/ (the core profile's
pages of desk/contracts: test_contracts.py). Review a change of either before committing it.

    python3 tck/fixtures/contracts/make_fixtures.py"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "python"))
from drishti_lake import contracts as K  # noqa: E402

RECORDS = [
    {"POSITION_ID": "P-1", "BUSINESS_DATE": "2026-10-27", "DESK": "D1", "QUANTITY": 3, "BASE_PV": 1.5, "SCENARIO_PV": [1, 2.5], "TRADE_IDS": ["T1"],
     "ACTIVE": True, "UPDATED_AT": "2026-10-27T10:00:00Z", "TIER": 1.0, "KYC": {"STATUS": "OK", "CHECKED_ON": "2026-10-01"},
     "LEGS": [{"INDEX": 0, "CCY": "USD"}]},
    {"POSITION_ID": "P-2", "DESK": "D2"},
    {"POSITION_ID": "", "DESK": None, "BUSINESS_DATE": "2026-10-27"},
    {"POSITION_ID": "X-3", "BUSINESS_DATE": "2026-10-27", "DESK": "D9", "COLOUR": "red", "BOOK": "B1"},
    {"POSITION_ID": "P-4", "DESK": "D1", "QUANTITY": 1.0, "BASE_PV": "12", "ACTIVE": 1, "UPDATED_AT": "2026-10-27 10:00", "TIER": True},
    {"POSITION_ID": "P-5", "DESK": "D1", "SCENARIO_PV": [1, None, "x"], "TRADE_IDS": ["T1", ""], "NET_PV": 4.0},
    {"POSITION_ID": "P-6", "DESK": "D1", "SCENARIO_PV": [], "KYC": {"STATUS": "NO", "EXTRA": 1}, "LEGS": [{"CCY": "usd", "PV": 1}, 7]},
    {"POSITION_ID": "P-7", "DESK": "D2", "KYC": "OK", "LEGS": {"INDEX": 1}, "TIER": 4, "BUSINESS_DATE": "27/10/2026"},
    {"POSITION_ID": 8, "DESK": "D2", "QUANTITY": 12345678901234567890, "TIER": 2, "KYC": {"STATUS": "", "CHECKED_ON": "2026-1-1"}},
    {"POSITION_ID": "P-9", "DESK": "D1", "TIER": 2.0, "BASE_PV": -0.0, "UPDATED_AT": "2026-10-27T10:00:00.123+05:30", "TRADE_IDS": []},
]


def pages() -> dict:
    files = sorted((HERE / "desk" / "contracts").glob("*.contract.yaml"))
    entries = [K.Entry(K.load(f)["kind"], K.load(f)["kind"], "desk", f, K.load(f)) for f in files]
    return K.render(K.PageProfile(), [e for e in entries if e.contract["status"] != "planned"],
                    [e for e in entries if e.contract["status"] == "planned"])


def main():
    c = K.Contract.read(HERE / "desk" / "contracts" / "position.contract.yaml")
    out = [{"record": r, "problems": K.record(c, r)} for r in RECORDS]
    (HERE / "records.json").write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    K.write(pages(), HERE / "pages")


if __name__ == "__main__":
    main()
