# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The package's tests import it from python/ (no install), and find the repository's shared fixtures (tck/fixtures)."""
import pathlib
import sys

PYTHON = pathlib.Path(__file__).resolve().parents[1]
ROOT = PYTHON.parent
if str(PYTHON) not in sys.path:
    sys.path.insert(0, str(PYTHON))
