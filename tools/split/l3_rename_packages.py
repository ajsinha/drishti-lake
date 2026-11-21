# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Move step L3, the rename commit: every file under the folders given gets this repository's header line and the host's packages
renamed to this repository's (docs/MOVE_ALLOWLIST.md); a host type the moved code still names points at its copy in
com.ash.lake.hostcompat (java/SPLIT_EXEMPTIONS.md). Usage: python3 tools/split/l3_rename_packages.py java"""
import pathlib
import re
import sys

PACKAGES = [  # longest first: a word boundary ends each
    ("com.ash.drishti.plugin.icebergext", "com.ash.lake.iceberg.external"),
    ("com.ash.drishti.plugin.iceberg", "com.ash.lake.iceberg.table"),
    ("com.ash.drishti.plugin.delta", "com.ash.lake.delta.table"),
    ("com.ash.drishti.lakeconfig", "com.ash.lake.config"),
    ("com.ash.drishti.deltalake", "com.ash.lake.delta"),
    ("com.ash.drishti.lakeload", "com.ash.lake.load"),
    ("com.ash.drishti.dataload", "com.ash.lake.inbox"),
    ("com.ash.drishti.lakeio", "com.ash.lake.io"),
    ("com.ash.drishti.iceberg", "com.ash.lake.iceberg"),
    ("com.ash.drishti.testkit", "com.ash.lake.tck"),
    ("com.ash.drishti.testroots", "com.ash.lake.tck"),
]
TAKEN_IN = {"FileDates", "RowRetention", "TempFiles", "JvmTempDir"}           # moved with their history into com.ash.lake.common
HOSTCOMPAT = {
    "ChannelIo", "ColumnSet", "DataDir", "DrishtiException", "ErrorCode", "IdText", "ListValues", "LoadGuard", "PackFolders",
    "NoProxy", "ProxySettings", "SiteProxy", "ConfigResolution", "ModuleHomes", "ModuleTemplates", "PackResolver",
    "ResolutionSettings", "Roots", "SettingSpec", "SingleFlight", "CertInfo", "Secrets", "TlsContexts", "TlsException",
    "TlsMaterial", "TlsSettings", "UnreadableData", "VectorReads", "VectorSink", "ConnectorSecrets", "Extensions",
    "ModuleLoaders", "NamedResources", "RowFeed", "DomainVocabulary", "Placeholders", "SettingsFile", "ProxyException",
    "PemReader", "Vocabularies", "Der",
}
HOST_TYPE = re.compile(r"com\.ash\.drishti\.(?:api(?:\.[a-z]\w*)?|common|settingsfile|kernel\.api\.vocabulary)\.([A-Z]\w*)")
OLD_LINE = "Project Drishti · Any data. Any domain. One grammar."
NEW_LINE = "Drishti Lake · Delta, Iceberg and plain files, loaded and read as one lake."


def host_type(m):
    name = m.group(1)
    if name in TAKEN_IN:
        return "com.ash.lake.common." + name
    if name in HOSTCOMPAT:
        return "com.ash.lake.hostcompat." + name
    return m.group(0)


def rewrite(text):
    text = text.replace(OLD_LINE, NEW_LINE)
    for old, new in PACKAGES:
        text = re.sub(re.escape(old) + r"\b", new, text)
    text = text.replace("package com.ash.drishti.common;", "package com.ash.lake.common;")   # the three taken in
    return HOST_TYPE.sub(host_type, text)


def main(roots):
    n = 0
    for root in roots:
        for f in pathlib.Path(root).rglob("*"):
            if not f.is_file() or f.suffix in (".parquet", ".avro", ".gz", ".zst", ".crc", ".bin"):
                continue
            try:
                t = f.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            o = rewrite(t)
            if o != t:
                f.write_text(o, encoding="utf-8")
                n += 1
    print(n, "files rewritten")


if __name__ == "__main__":
    main(sys.argv[1:])
