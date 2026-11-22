# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""target.properties on the Python path: the product's names as Delta's own, in the Java TableProperties' words (the kit's
properties/retention-and-append-only case holds the two equal end to end)."""
import pytest

from drishti_lake import props


def test_the_products_names_become_deltas():
    out = props.for_delta({"lake.append-only": True, "lake.retention-hours": "61368", "delta.checkpointInterval": "10"})
    assert out == {"delta.checkpointInterval": "10", "delta.appendOnly": "true", "delta.deletedFileRetentionDuration": "interval 2557 days",
                   "delta.logRetentionDuration": "interval 2557 days"}
    assert props.for_delta({"history.expire.max-snapshot-age-ms": str(36 * 3_600_000)})["delta.deletedFileRetentionDuration"] == "interval 36 hours"
    assert props.append_only(out)


@pytest.mark.parametrize("given, words", [
    ({"dri" + "shti.append-only": "true"}, "the product's properties are lake.*"),
    ({"lake.append-only": "yes"}, "true or false, not yes"),
    ({"delta.deletedFileRetentionDuration": "7 days"}, "interval N days"),
    ({"lake.retention-hours": "24", "history.expire.max-snapshot-age-ms": "3600000"}, "name one property with different values"),
])
def test_refused_in_the_java_words(given, words):
    with pytest.raises(ValueError, match=words.replace("*", r"\*")):
        props.for_delta(given)


def test_an_append_only_table_refuses_a_replace():
    props.refuse_replace({}, "risk/t", False)
    with pytest.raises(ValueError) as e:
        props.refuse_replace({"delta.appendOnly": "true"}, "risk/t", True)
    assert str(e.value) == ("risk/t is append-only (lake.append-only): a replace of partitions (load mode overwrite-partitions) is refused; "
                            "the table takes appends only")
