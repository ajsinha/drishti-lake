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
package com.ash.lake.load;

import com.ash.lake.LakeRuntime;
import com.ash.lake.files.FilesStore;
import com.ash.lake.spi.TableStore;
import java.util.Locale;

/**
 * The table store of a format: the one the runtime registers for it ({@link LakeRuntime.Builder#store}), else the product's own Delta,
 * Iceberg or files store. Nothing is found on the class path.
 */
public final class Stores {

    private static final DeltaStore DELTA = DeltaStore.create();
    private static final IcebergStore ICEBERG = IcebergStore.create();
    private static final FilesStore FILES = FilesStore.create();

    private Stores() {
    }

    /** The built-in store of {@code format} ({@code delta}, {@code iceberg} or {@code files}). */
    public static TableStore builtIn(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case DeltaStore.FORMAT -> DELTA;
            case IcebergStore.FORMAT -> ICEBERG;
            case FilesStore.FORMAT -> FILES;
            default -> throw new IllegalArgumentException("no built-in store for format " + format + " (delta, iceberg or files; register a store "
                    + "for another format when the runtime is built)");
        };
    }

    /** Whether {@code format} names a built-in store. */
    public static boolean isBuiltIn(String format) {
        return format != null && java.util.Set.of(DeltaStore.FORMAT, IcebergStore.FORMAT, FilesStore.FORMAT).contains(format.toLowerCase(Locale.ROOT));
    }

    /** The runtime's store of {@code format}, else the built-in one. */
    public static TableStore of(LakeRuntime runtime, String format) {
        return runtime.store(format).orElseGet(() -> builtIn(format));
    }
}
