<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Build, install and the IDE

The one build guide of this repository.

## Prepare your environment

| Need | Version | Used for |
|---|---|---|
| JDK | 21 or later | the Java build (`java/mvnw` downloads Maven) |
| Python | 3.13 (3.11 is the minimum) | the Python package, the parity kit, the checks; no virtual environment: use the Python you choose |
| [uv](https://docs.astral.sh/uv/) | any recent | the Delta interop tests (delta-rs at pinned versions), the wheel build; **required, never skipped** |
| Drishti Tools | an installed tag | a build dependency (`com.ash.tools`, version `drishti.tools.version` in `java/lake-bom/pom.xml`) |

No executable has to be placed on the machine: the build is Java and Python only (nothing native; the S3 simulator used by the tests runs
in-process, so the tests run on Windows too).

## Install order

The Drishti family installs in this order: **Drishti Tools -> Drishti Rachana -> Drishti Lake -> Drishti** (the order of the platform's `build_all.py` and its IntelliJ launcher `platform-clean-install`; each from its release tag). Each is installed from
its release tag, never from a branch, into `~/.m2/repository/com/ash/<product>/` (Windows `C:\Users\<user>\.m2\repository\com\ash\<product>\`):

```text
git checkout vX.Y.Z && cd java && ./mvnw clean install -DskipTests     # Windows: mvnw.cmd
```

Lake needs Drishti Tools installed first. Until its tag is installed you can build Tools into a folder of your own and point this build
at it (every `mvnw` and every tool of this repository reads `MAVEN_ARGS`):

```text
cd drishti-tools/java && ./mvnw install -DskipTests -Dmaven.repo.local=/path/to/scratch-m2
export MAVEN_ARGS="-Dmaven.repo.local=/path/to/scratch-m2 -Dmaven.repo.local.tail=$HOME/.m2/repository"
```

## Build and test

```text
cd java
./mvnw clean verify                    # every module, test and guard; four test JVMs (lake.test.forks)
./mvnw clean verify -DskipTests        # compile and package
./mvnw clean verify -pl lake-load -am  # one module and what it needs
```

Every path a test writes is under the module's `target/`; Maven's temporary files go to `java/build-tmp/` (set `LAKE_MAVEN_TMP` for another
folder). The Python package's tests: `cd python && python -m pytest` (needs `deltalake`, `pyarrow`, `pyyaml`, `jsonschema`: `pip install -e .`
in `python/` installs the pinned versions).

The whole check, as a release runs it (build with every test and guard, delta-rs interop, the Python tests, the parity kit twice, its
gates and the comparison with the reference results): `python3 tools/ci/check.py` ([TCK.md](TCK.md)). There is no hosted CI.

## IntelliJ IDEA

Open `java/pom.xml` as a project; project SDK JDK 21; **Settings, Build Tools, Maven, Runner, JRE: Use Project JDK**. Run configurations are in
`run-configs/` at the repository root, folders **IntelliJ-lake-...** and **PyCharm-lake-...** (open the repository root as the project and link `java/pom.xml` in the Maven tool window):

| Configuration | Runs |
|---|---|
| **Lake: build all** | the build without tests |
| **Lake: test** | the full build with every test and guard |
| **Lake: install release (tag only)** | `install`; on a release tag only |
| **Lake: run loader (CLI)** | `com.ash.lake.load.LakeLoad`; give it `--config` of your own (see the loader section of [GUIDE.md](GUIDE.md)) |
| **Lake: run check (CLI)** | `com.ash.lake.load.LakeCheck` |

IntelliJ Community has no Node support; nothing here needs Node.

## PyCharm

Open the repository root; the same `run-configs/` holds the Python launchers: **Lake: test TCK (this repository)** (the parity kit over the built
modules) and **Lake: check all (build, TCK twice, reference)** (also **Lake: test Python**, **Lake: maintain release plan (dry run)**) (`tools/ci/check.py`). The Python package is in `python/`: mark `python/` as sources,
use a Python 3.13 interpreter (3.11 is the minimum) with the package's dependencies, and run `python/tests` with pytest. One root, one `run-configs/`: IntelliJ and PyCharm read the same launchers.

## VS Code

**File, Open Workspace from File**, choose `drishti-lake.code-workspace` (a visible file at the repository root; no hidden folder). Recommended
extensions: Extension Pack for Java, Python. Edit the JDK path in `java.configuration.runtimes` (Windows `C:\jdk-21`). **Terminal, Run Task**
has the Maven tasks, the TCK and the check; **Run and Debug** has the two Java programs (`LakeLoad`, `LakeCheck`) and the TCK.

## Windows and company networks: `PKIX path building failed`

A company network that re-signs HTTPS with its own root certificate stops Maven on its first download. Windows trusts that root; the JDK does
not. One VM option fixes it, with no file change and no administrator rights:

```text
-Djavax.net.ssl.trustStoreType=Windows-ROOT
```

IntelliJ: **Maven, Runner, VM Options** and **Maven, Importing, VM options for importer**. A terminal: `setx MAVEN_OPTS
"-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then a new terminal. Alternatives: your company's mirror in `%USERPROFILE%\.m2\settings.xml`, or the
root added to the JDK with `keytool -importcert -cacerts`.

## The repository's rules, as tests

`lake-guards` runs with the build: the public API (`java/api-surface.txt`, rewritten by the build when an API change is reviewed; no Delta
Kernel, Iceberg or Parquet type in a public signature), the dependency allow-list, the names a host may not appear under (`java/SPLIT_EXEMPTIONS.md`
lists what the code still owes its first host; the list only shrinks), the copyright header on every source and 2,500 lines per file at most.
