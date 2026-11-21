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

import com.ash.lake.spi.LakeCatalog;
import com.ash.lake.spi.Storage;
import com.ash.lake.spi.StorageProvider;
import com.ash.lake.spi.TableStore;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One instance of the product (design section 3.1, LK-6): its options, the host's catalog, the settings its lake files and named lakes
 * are resolved with, the storages it reaches beyond the local disk and S3, and the parts it keeps for its life (the readers' caches).
 * Everything is registered explicitly when it is built: there is no static registry, no {@code ServiceLoader} and no system property
 * read behind a caller's back, so two runtimes in one JVM never see each other's settings, providers or caches.
 *
 * <p>An embedding host builds one with {@link #builder()} and hands it to the code it calls. A command-line entry point builds one
 * with {@link #fromProcess()}: the process's options and settings ({@code -D}, then the environment), no catalog, the built-in
 * storages only.
 */
public final class LakeRuntime {

    private final LakeOptions options;
    private final LakeCatalog catalog;
    private final Function<String, String> settings;
    private final Supplier<Collection<String>> settingNames;
    private final Map<String, StorageProvider> providers;
    private final Map<String, TableStore> stores;
    private final LakeRoots roots;
    private final Map<Class<?>, Object> parts = new ConcurrentHashMap<>();

    private LakeRuntime(Builder b) {
        this.options = b.options;
        this.catalog = b.catalog;
        this.settings = b.settings;
        this.settingNames = b.settingNames;
        this.providers = Map.copyOf(b.providers);
        this.stores = Map.copyOf(b.stores);
        this.roots = new LakeRoots(options);
    }

    /** A runtime of exactly what is registered: empty options, no catalog, no settings, the built-in storages only. */
    public static Builder builder() {
        return new Builder();
    }

    /** What a command-line entry point uses: the process's options and settings, no catalog, the built-in storages only. */
    public static LakeRuntime fromProcess() {
        return builder().options(LakeOptions.fromProcess()).settings(Placeholders::process, LakeRuntime::processNames).build();
    }

    /** The options. */
    public LakeOptions options() {
        return options;
    }

    /** The host's catalog ({@link LakeCatalog#NONE} with no host). */
    public LakeCatalog catalog() {
        return catalog;
    }

    /** The named roots (install, configuration, data and connectors folders) of these options. */
    public LakeRoots roots() {
        return roots;
    }

    /**
     * One setting a lake file refers to ({@code ${name}}) or a named lake's ({@code lake.<name>.root}), as the host sees it; null when
     * nothing names it.
     */
    public String setting(String key) {
        return settings.apply(key);
    }

    /** The settings view itself, for a reader that takes a lookup function. */
    public Function<String, String> settings() {
        return settings;
    }

    /** Every setting name the host's sources hold (for the named lakes' sections). */
    public Collection<String> settingNames() {
        return List.copyOf(settingNames.get());
    }

    /** The registered storage providers, by scheme. */
    public Map<String, StorageProvider> providers() {
        return providers;
    }

    /** The registered table stores, by format. */
    public Map<String, TableStore> stores() {
        return stores;
    }

    /** The store registered here for {@code format} (lower case), if any. */
    public Optional<TableStore> store(String format) {
        return format == null ? Optional.empty() : Optional.ofNullable(stores.get(format.toLowerCase(Locale.ROOT)));
    }

    /** The provider of {@code path}'s scheme, if one is registered here. */
    public Optional<StorageProvider> provider(String path) {
        String s = scheme(path);
        return s == null ? Optional.empty() : Optional.ofNullable(providers.get(s));
    }

    /** Whether a registered provider serves {@code path}'s scheme. */
    public boolean supports(String path) {
        return provider(path).isPresent();
    }

    /**
     * The storage of {@code path} from its scheme's provider.
     *
     * @throws UnsupportedOperationException when no provider here serves the scheme
     */
    public Storage storage(String path) {
        return provider(path).orElseThrow(() -> new UnsupportedOperationException("no storage serves " + scheme(path)
                + ":// in this runtime (register its StorageProvider when the runtime is built)")).storage(path);
    }

    /** Whether {@code path} is under a memory-only scheme ({@link StorageProvider#memoryOnly()}): nothing of it may reach a disk. */
    public boolean memoryOnly(String path) {
        return provider(path).map(StorageProvider::memoryOnly).orElse(false);
    }

    /**
     * A part this runtime keeps for its life, made on first ask (a reader's cache, sized by these options): one per runtime and type,
     * never shared with another runtime.
     */
    public <T> T part(Class<T> type, Supplier<? extends T> make) {
        Object p = parts.get(type);
        if (p == null) {
            T made = Objects.requireNonNull(make.get(), "part");
            p = parts.putIfAbsent(type, made);
            if (p == null) {
                p = made;
            }
        }
        return type.cast(p);
    }

    /** The scheme a provider would serve {@code path} under, lower case; null for a local path, a drive letter or no scheme. */
    static String scheme(String path) {
        if (path == null) {
            return null;
        }
        String p = path.strip();
        int colon = p.indexOf(':');
        if (colon < 2) {
            return null;                                           // no scheme, or a Windows drive letter
        }
        for (int i = 0; i < colon; i++) {
            char c = p.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return null;
            }
        }
        return p.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    private static Collection<String> processNames() {
        Set<String> out = new LinkedHashSet<>(System.getProperties().stringPropertyNames());
        out.addAll(System.getenv().keySet());
        return out;
    }

    /** Builds a runtime; everything it holds is registered here. */
    public static final class Builder {
        private LakeOptions options = LakeOptions.builder().build();
        private LakeCatalog catalog = LakeCatalog.NONE;
        private Function<String, String> settings = k -> null;
        private Supplier<Collection<String>> settingNames = List::of;
        private final Map<String, StorageProvider> providers = new TreeMap<>();
        private final Map<String, TableStore> stores = new TreeMap<>();

        private Builder() {
        }

        /** The options ({@link LakeOptions#builder()} or {@link LakeOptions#fromProcess()}). */
        public Builder options(LakeOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** The host's catalog. */
        public Builder catalog(LakeCatalog catalog) {
            this.catalog = catalog == null ? LakeCatalog.NONE : catalog;
            return this;
        }

        /**
         * The host's settings: {@code lookup} reads one key (a lake file's {@code ${name}}, a named lake's {@code lake.<name>.root}),
         * {@code names} lists every key its sources hold.
         */
        public Builder settings(Function<String, String> lookup, Supplier<Collection<String>> names) {
            this.settings = Objects.requireNonNull(lookup, "lookup");
            this.settingNames = names == null ? List::of : names;
            return this;
        }

        /** The host's settings as a map. */
        public Builder settings(Map<String, String> values) {
            Map<String, String> copy = Map.copyOf(values);
            return settings(copy::get, copy::keySet);
        }

        /** Storages for more schemes ({@code mem://}); the local disk and S3 are built in and cannot be claimed. */
        public Builder storage(StorageProvider... more) {
            for (StorageProvider p : more) {
                String scheme = Objects.requireNonNull(p.scheme(), "scheme").toLowerCase(Locale.ROOT);
                if (scheme.length() < 2 || scheme.equals("file") || scheme.startsWith("s3")) {
                    throw new IllegalArgumentException("storage provider " + p.getClass().getName() + " claims scheme " + scheme
                            + "://, which the product serves itself");
                }
                StorageProvider before = providers.putIfAbsent(scheme, p);
                if (before != null) {
                    throw new IllegalArgumentException("two storage providers claim scheme " + scheme + "://: " + before.getClass().getName()
                            + " and " + p.getClass().getName());
                }
            }
            return this;
        }

        /** Table stores for more formats, or a store of its own for a built-in one; one store per format. */
        public Builder store(TableStore... more) {
            for (TableStore t : more) {
                String format = Objects.requireNonNull(t.format(), "format").toLowerCase(Locale.ROOT);
                TableStore before = stores.putIfAbsent(format, t);
                if (before != null) {
                    throw new IllegalArgumentException("two table stores claim format " + format + ": " + before.getClass().getName() + " and "
                            + t.getClass().getName());
                }
            }
            return this;
        }

        public LakeRuntime build() {
            return new LakeRuntime(this);
        }
    }
}
