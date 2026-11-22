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
package com.ash.lake.io.read;

import com.ash.lake.common.LakeError;
import com.ash.lake.common.LakeException;
import com.ash.lake.read.ReadProblem;
import com.ash.lake.read.ReadProblems;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The read problems of one lake reader (READ_API.md 2.8), written once for every store: a read that fails records its classified reason
 * for its table and place (a date, the log, the listing), and the next good read of the same place clears it; a table's new version
 * forgets what older versions failed. Thread-safe: reads on many threads record and clear at once. The reason words are the store's
 * ({@code reason}): what is wrong in words a reader can act on.
 */
public final class Problems implements ReadProblems {

    private static final System.Logger LOGGER = System.getLogger(Problems.class.getName());
    private static final int MAX_REASON = 300;

    private record Listing(String reason, Instant at, Instant keptFrom) {}

    private final String source;
    private final Function<Throwable, String> reason;
    private final Map<String, ReadProblem> byKey = new ConcurrentHashMap<>();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();

    /** Problems of the reader of {@code source} (for the log), with the store's reason words. */
    public Problems(String source, Function<Throwable, String> reason) {
        this.source = source;
        this.reason = reason;
    }

    private static String key(String table, String where) {
        return table + "\u001f" + where;
    }

    /** A read that may fail. */
    @FunctionalInterface
    public interface Read<T> {
        T run() throws Exception;
    }

    /**
     * Runs a read of {@code where} ({@code null}: the table's log) of {@code table} at {@code version}: its failure recorded and thrown as a
     * {@link LakeException} ({@code LK-1006}) with the reason, its success clearing what was recorded there.
     */
    public <T> T read(String table, java.time.LocalDate date, long version, Read<T> body) {
        String where = date == null ? "log" : date.toString();
        T out;
        try {
            out = body.run();
        } catch (Exception e) {
            CallerFailure own = callerFailure(e);
            if (own != null) {
                throw own.failure();                             // the caller's own sink refused: its exception as it was, no read problem
            }
            String why = failed(table, where, version, e);
            throw new LakeException(LakeError.UNREADABLE, table + " " + where + ": " + why, e);
        }
        ok(table, where);
        return out;
    }

    /**
     * The caller's own failure inside a read: its sink ({@link #caller}) threw. It is not a read problem (the data was read; the caller
     * refused it, a duplicate row for one): {@link #read} records nothing and throws the caller's exception unchanged.
     */
    public static final class CallerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CallerFailure(RuntimeException e) {
            super(e.getMessage(), e, false, false);
        }

        /** The caller's exception. */
        public RuntimeException failure() {
            return (RuntimeException) getCause();
        }
    }

    /** The caller's failure in {@code e} or its causes (a parallel read wraps a worker's), null when the read itself failed. */
    static CallerFailure callerFailure(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof CallerFailure f) {
                return f;
            }
        }
        return null;
    }

    private static RuntimeException own(RuntimeException e) {
        return e instanceof CallerFailure ? e : new CallerFailure(e);
    }

    /** The caller's vector sink, its exceptions marked as the caller's ({@link CallerFailure}). */
    public static com.ash.lake.read.VectorSink caller(com.ash.lake.read.VectorSink sink) {
        return (id, values, length, texts) -> {
            try {
                sink.row(id, values, length, texts);
            } catch (RuntimeException e) {
                throw own(e);
            }
        };
    }

    /** The caller's document sink, its exceptions marked as the caller's. */
    public static com.ash.lake.read.DocumentSink caller(com.ash.lake.read.DocumentSink sink) {
        return (id, json) -> {
            try {
                sink.document(id, json);
            } catch (RuntimeException e) {
                throw own(e);
            }
        };
    }

    /** The caller's row sink, its exceptions marked as the caller's. */
    public static com.ash.lake.read.RowSink caller(com.ash.lake.read.RowSink sink) {
        return values -> {
            try {
                sink.row(values);
            } catch (RuntimeException e) {
                throw own(e);
            }
        };
    }

    /** Records that reading {@code where} of {@code table} failed with {@code e}; returns the reason recorded. */
    public String failed(String table, String where, long version, Throwable e) {
        String why = shorten(reason.apply(e));
        ReadProblem was = byKey.put(key(table, where), new ReadProblem(table, where, version, why, Instant.now()));
        if (was == null || !was.reason().equals(why)) {
            LOGGER.log(System.Logger.Level.WARNING, "{0}: {1} {2} cannot be read: {3}", source, table, where, why);
        }
        return why;
    }

    /** A read of {@code where} of {@code table} succeeded: a problem recorded there is over. */
    public void ok(String table, String where) {
        if (byKey.isEmpty()) {
            return;                                              // the common case: nothing to clear, no allocation
        }
        if (byKey.remove(key(table, where)) != null) {
            LOGGER.log(System.Logger.Level.INFO, "{0}: {1} {2} is readable again", source, table, where);
        }
    }

    /** The table is now at {@code version}: failures seen at other versions may be fixed, so they are forgotten. */
    public void version(String table, long version) {
        if (!byKey.isEmpty()) {
            byKey.values().removeIf(p -> p.table().equals(table) && p.version() >= 0 && p.version() != version);
        }
    }

    /** The table could not be listed again; what was listed before (from {@code keptFrom}, null when none) is served meanwhile. */
    public void listingFailed(String table, Throwable e, Instant keptFrom) {
        Listing was = listings.get(table);
        listings.put(table, new Listing(shorten(reason.apply(e)), was == null ? Instant.now() : was.at(), keptFrom));
    }

    public void listingOk(String table) {
        listings.remove(table);
    }

    @Override
    public Optional<String> listing(String table) {
        Listing l = listings.get(table);
        if (l == null) {
            return Optional.empty();
        }
        return Optional.of("its list of " + table + " entities could not be rebuilt (" + l.reason() + "); "
                + (l.keptFrom() == null ? "it lists none" : "it lists those of " + l.keptFrom()));
    }

    @Override
    public List<ReadProblem> all() {
        List<ReadProblem> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparing(ReadProblem::at));
        return out;
    }

    @Override
    public boolean isEmpty() {
        return byKey.isEmpty() && listings.isEmpty();
    }

    @Override
    public String summary(int max) {
        List<String> parts = new ArrayList<>();
        for (ReadProblem p : all()) {
            parts.add(p.table() + " " + p.where() + ": " + p.reason());
        }
        listings.forEach((table, l) -> {
            if (parts.stream().noneMatch(s -> s.startsWith(table + " "))) {
                parts.add(table + " ids: " + l.reason());
            }
        });
        int shown = Math.min(max, parts.size());
        String line = String.join("; ", parts.subList(0, shown));
        return parts.size() > shown ? line + "; and " + (parts.size() - shown) + " more" : line;
    }

    /** The words of a failure: its message, else its class; the first cause with a message when the top one has none. */
    public static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t instanceof java.io.FileNotFoundException || t instanceof java.nio.file.NoSuchFileException
                        ? "a file is missing: " + t.getMessage() : t.getMessage();
            }
        }
        return e.getClass().getSimpleName();
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() <= MAX_REASON ? s : s.substring(0, MAX_REASON) + "…";
    }
}
