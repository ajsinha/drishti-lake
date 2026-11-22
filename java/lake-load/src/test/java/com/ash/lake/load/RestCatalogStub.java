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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTObjectMapper;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;

/**
 * An Iceberg REST catalog in the test process (as AWS Glue's Iceberg endpoint, Polaris or Nessie answer): the protocol's config,
 * namespace and table calls (list, create, load, commit) through iceberg-core's own {@link CatalogHandlers}, over a catalog that keeps each
 * table's metadata pointer in memory and its files on the local disk. Every call is recorded with its {@code Authorization} header.
 */
final class RestCatalogStub implements AutoCloseable {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final Backing catalog;
    private final HttpServer server;
    private final ObjectMapper json = RESTObjectMapper.mapper();

    RestCatalogStub(FileIO io, String warehouse) throws IOException {
        catalog = new Backing(io, warehouse);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        calls.add(method + " " + path + " " + ex.getRequestHeaders().getFirst("Authorization"));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] p = path.replaceAll("^/+|/+$", "").split("/");
        try {
            Object out;
            if (path.endsWith("/metrics")) {
                out = null;
            } else if (path.equals("/v1/config")) {
                out = ConfigResponse.builder().build();
            } else if (p.length == 2 && p[1].equals("namespaces")) {
                out = method.equals("POST") ? CatalogHandlers.createNamespace(catalog, json.readValue(body, CreateNamespaceRequest.class))
                        : CatalogHandlers.listNamespaces(catalog, Namespace.empty());
            } else if (p.length == 3 && p[1].equals("namespaces")) {
                Namespace ns = RESTUtil.decodeNamespace(p[2]);
                if (method.equals("HEAD")) {
                    CatalogHandlers.namespaceExists(catalog, ns);
                    out = null;
                } else {
                    out = CatalogHandlers.loadNamespace(catalog, ns);
                }
            } else if (p.length == 4 && p[3].equals("tables")) {
                Namespace ns = RESTUtil.decodeNamespace(p[2]);
                out = method.equals("POST") ? CatalogHandlers.createTable(catalog, ns, json.readValue(body, CreateTableRequest.class))
                        : CatalogHandlers.listTables(catalog, ns);
            } else if (p.length == 5 && p[3].equals("tables")) {
                TableIdentifier id = TableIdentifier.of(RESTUtil.decodeNamespace(p[2]), RESTUtil.decodeString(p[4]));
                if (method.equals("HEAD")) {
                    CatalogHandlers.tableExists(catalog, id);
                    out = null;
                } else if (method.equals("POST")) {
                    out = CatalogHandlers.updateTable(catalog, id, json.readValue(body, UpdateTableRequest.class));
                } else {
                    out = CatalogHandlers.loadTable(catalog, id);
                }
            } else {
                throw new UnsupportedOperationException(method + " " + path);
            }
            answer(ex, out == null ? 204 : 200, out == null ? null : json.writeValueAsString(out));
        } catch (NoSuchTableException | NoSuchNamespaceException e) {
            error(ex, 404, e);
        } catch (AlreadyExistsException | CommitFailedException e) {
            error(ex, 409, e);
        } catch (UnsupportedOperationException e) {
            error(ex, 406, e);
        } catch (RuntimeException e) {
            error(ex, 500, e);
        }
    }

    private void error(HttpExchange ex, int code, Exception e) throws IOException {
        answer(ex, code, json.writeValueAsString(ErrorResponse.builder().responseCode(code).withType(e.getClass().getSimpleName())
                .withMessage(String.valueOf(e.getMessage())).build()));
    }

    private static void answer(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    /** The catalog behind the endpoint: each table's metadata file by its identifier; namespaces as a set. */
    static final class Backing extends BaseMetastoreCatalog implements SupportsNamespaces {
        final Map<TableIdentifier, String> pointers = new ConcurrentHashMap<>();
        private final Map<Namespace, Map<String, String>> namespaces = new ConcurrentHashMap<>();
        private final FileIO io;
        private final String warehouse;

        Backing(FileIO io, String warehouse) {
            this.io = io;
            this.warehouse = warehouse;
        }

        /** A table written elsewhere (its metadata file) taken into the catalog. */
        void adopt(TableIdentifier id, String metadataFile) {
            namespaces.putIfAbsent(id.namespace(), Map.of());
            pointers.put(id, metadataFile);
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        protected TableOperations newTableOps(TableIdentifier id) {
            return new BaseMetastoreTableOperations() {
                @Override
                protected String tableName() {
                    return id.toString();
                }

                @Override
                public FileIO io() {
                    return io;
                }

                @Override
                protected void doRefresh() {
                    refreshFromMetadataLocation(pointers.get(id));
                }

                @Override
                protected void doCommit(TableMetadata base, TableMetadata metadata) {
                    String location = writeNewMetadataIfRequired(base == null, metadata);
                    synchronized (pointers) {
                        if (!Objects.equals(pointers.get(id), base == null ? null : base.metadataFileLocation())) {
                            throw new CommitFailedException("the table " + id + " changed meanwhile");
                        }
                        pointers.put(id, location);
                    }
                }
            };
        }

        @Override
        protected String defaultWarehouseLocation(TableIdentifier id) {
            return warehouse + "/" + String.join("/", id.namespace().levels()) + "/" + id.name();
        }

        @Override
        public List<TableIdentifier> listTables(Namespace ns) {
            if (!namespaces.containsKey(ns)) {
                throw new NoSuchNamespaceException("no namespace %s", ns);
            }
            List<TableIdentifier> out = new ArrayList<>();
            pointers.keySet().stream().filter(t -> t.namespace().equals(ns)).sorted(java.util.Comparator.comparing(TableIdentifier::toString))
                    .forEach(out::add);
            return out;
        }

        @Override
        public boolean dropTable(TableIdentifier id, boolean purge) {
            return pointers.remove(id) != null;
        }

        @Override
        public void renameTable(TableIdentifier from, TableIdentifier to) {
            throw new UnsupportedOperationException("rename");
        }

        @Override
        public void createNamespace(Namespace ns, Map<String, String> metadata) {
            if (namespaces.putIfAbsent(ns, Map.copyOf(metadata)) != null) {
                throw new AlreadyExistsException("namespace %s exists", ns);
            }
        }

        @Override
        public List<Namespace> listNamespaces(Namespace ns) {
            return namespaces.keySet().stream().sorted(java.util.Comparator.comparing(Namespace::toString)).toList();
        }

        @Override
        public Map<String, String> loadNamespaceMetadata(Namespace ns) {
            Map<String, String> m = namespaces.get(ns);
            if (m == null) {
                throw new NoSuchNamespaceException("no namespace %s", ns);
            }
            return m;
        }

        @Override
        public boolean dropNamespace(Namespace ns) {
            return namespaces.remove(ns) != null;
        }

        @Override
        public boolean setProperties(Namespace ns, Map<String, String> properties) {
            return false;
        }

        @Override
        public boolean removeProperties(Namespace ns, java.util.Set<String> properties) {
            return false;
        }
    }
}
