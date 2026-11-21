# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""``python -m drishti_lake load ...`` (the Java loader's command line: ``load --help``) and ``python -m drishti_lake contracts ...``
(``contracts --help``), and the Java tools through the product's class path (``java``)."""
import sys

from . import load


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        print("usage: python -m drishti_lake load --config FILE [options]   (load --help)\n"
              "       python -m drishti_lake contracts check|pages ...          (contracts --help)\n"
              "       python -m drishti_lake undo|retention|check|maintain|lakes ...   (the Java tools: LAKE_CLASSPATH)")
        return 0
    if argv[0] == "contracts":
        from . import contracts
        return contracts.main(argv[1:])
    from . import java
    if argv[0] in java.TOOLS:                         # the Java tools (undo, retention, check, maintain, lakes): one way per job
        return java.run(argv[0], argv[1:])
    if argv[0] != "load":
        print(f"drishti_lake: unknown command {argv[0]} (load, contracts, {', '.join(java.TOOLS)})")
        return 1
    return load.main(argv[1:])


if __name__ == "__main__":
    sys.exit(main())
