/*
 * Drishti Lake · Delta, Iceberg and plain files, loaded and read as one lake.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.lake;

import com.ash.tools.settings.SettingsFile;
import java.nio.file.Path;

/**
 * The named roots every default folder sits under, read from one runtime's options ({@link LakeRuntime#roots()}): the install
 * folder ({@code lake.home}), the configuration folder ({@code lake.config.dir}, default {@code <home>/config}), the data folder
 * ({@code lake.data.dir}, default {@code <home>/data}) and the connectors folder. A relative value is under the install folder; nothing
 * is ever taken from the process's working folder: with no install folder named, a default is refused with a message naming the setting.
 * One setting's value is made absolute by the shared rule, {@code com.ash.tools.settings.PathAnchors.anchorValue(key, value, home, HOME, problems)}.
 */
public final class LakeRoots {

    /** The install folder: the anchor of every default. */
    public static final String HOME = LakeOptions.HOME;
    /** Its environment variable. */
    public static final String HOME_ENV = SettingsFile.envName(LakeOptions.HOME);

    private final LakeOptions options;

    LakeRoots(LakeOptions options) {
        this.options = options;
    }

    /** The roots of {@code options}. */
    public static LakeRoots of(LakeOptions options) {
        return new LakeRoots(java.util.Objects.requireNonNull(options, "options"));
    }

    /** The install folder, absolute; refused when nothing names it. */
    public Path home() {
        String h = setting(HOME);
        if (h == null) {
            throw new IllegalStateException(HOME + " (" + HOME_ENV + ") is not set: name the install folder, or"
                    + " set the folder's own setting; a default folder is never taken from the process's working folder");
        }
        Path p = Path.of(h);
        if (!p.isAbsolute()) {
            throw new IllegalStateException(HOME + " (" + HOME_ENV + ") is '" + h + "', a relative path: give the install folder as an absolute path");
        }
        return p.normalize();
    }

    /** A path under the install folder, e.g. {@code home("sutras")}. */
    public String home(String sub) {
        return home().resolve(sub).normalize().toString();
    }

    /** The configuration folder. */
    public String config() {
        return base(LakeOptions.CONFIG_DIR, "config").toString();
    }

    /** A path under the configuration folder, e.g. {@code config("bi")}. */
    public String config(String sub) {
        return base(LakeOptions.CONFIG_DIR, "config").resolve(sub).normalize().toString();
    }

    /** The data folder. */
    public String data() {
        return base(LakeOptions.DATA_DIR, "data").toString();
    }

    /** A folder under the data folder as text joined with a slash, e.g. {@code under("delta")}. */
    public String under(String sub) {
        String r = data();
        return (r.endsWith("/") || r.endsWith("\\") ? r : r + "/") + sub;
    }

    /** A path under the data folder, e.g. {@code data("collab")}. */
    public String data(String sub) {
        return base(LakeOptions.DATA_DIR, "data").resolve(sub).normalize().toString();
    }

    /** The connectors folder: {@code lake.connectors.dir}, else {@code <config>/connectors}; absolute. */
    public Path connectors() {
        String v = setting(LakeOptions.CONNECTORS_DIR);
        return v == null ? Path.of(config("connectors")) : anchored(v);
    }

    /** A value as an absolute path: as given when absolute, else under the install folder (never the working folder). */
    public Path anchored(String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p.normalize() : home().resolve(p).normalize();
    }

    private Path base(String key, String underHome) {
        String v = setting(key);
        return v == null ? home().resolve(underHome) : anchored(v);
    }

    private String setting(String key) {
        return options.value(key);
    }
}
