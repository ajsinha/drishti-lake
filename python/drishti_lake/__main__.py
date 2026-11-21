# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""``python -m drishti_lake load ...`` (the Java loader's command line: ``load --help``)."""
import sys

from . import load


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        print("usage: python -m drishti_lake load --config FILE [options]   (load --help)")
        return 0
    if argv[0] != "load":
        print(f"drishti_lake: unknown command {argv[0]} (load)")
        return 1
    return load.main(argv[1:])


if __name__ == "__main__":
    sys.exit(main())
