# Offline-First Sync Agent

An enterprise-grade desktop and cloud solution demonstrating bidirectional data synchronization between an offline-capable JavaFX desktop client and a centralized Spring Boot backend.

## Key Features & Architecture

- **Offline-First Resilience:** Full Master-Detail CRUD operations (`Order` -> `OrderItem`) without network connectivity via local SQLite storage (`client_storage.db`).
- **Bidirectional Delta Synchronization:** Automated background scheduler (`ScheduledExecutorService`) and manual sync triggers executing a two-phase `Push -> Pull` cycle using incremental timestamps (`?since=`).
- **Sync Lifecycle State Machine:** Tracks local mutations using dirty flags (`SYNCED`, `DIRTY_CREATED`, `DIRTY_UPDATED`, `DIRTY_DELETED`, `CONFLICT`) with soft-delete support for offline deletions.
- **Optimistic Locking & Conflict Resolution:** Server-side version validation prevents silent data overwrites (`409` / conflict payload rejection). Includes a side-by-side **Compare Conflict Details** modal (`Local SQLite` vs `Server PostgreSQL`) supporting both *Client Wins* (`Force Local`) and *Server Wins* (`Discard Local`) strategies.
- **Desktop ERP UI:** Real-time search and status filtering, live summary calculations, line-item management with automatic order total recalculation, network status indicator (`Online` / `Offline`), and CSV export.

## Monorepo Structure

```
offline-sync-agent/
├── sync-core/          # Shared domain DTOs, sync payloads, and SyncStatus lifecycle enum
├── sync-server/        # Spring Boot 3 REST API, Spring Data JPA, PostgreSQL persistence & integration tests
├── desktop-client/     # JavaFX desktop application, SQLite JDBC DAO layer, HTTP sync client & unit tests
├── docker-compose.yml  # Containerized PostgreSQL & Spring Boot server setup
└── pom.xml             # Parent Maven multi-module configuration
```

## Tech Stack

- **Core & Client:** Java 21, JavaFX 21, SQLite (`sqlite-jdbc`), Java `HttpClient`, Jackson (`jsr310`)
- **Backend Server:** Spring Boot 3.3, Spring Data JPA / Hibernate, PostgreSQL 16
- **Testing & DevOps:** JUnit 5, Mockito, MockMvc, Testcontainers, Docker Compose, Maven Multi-Module

## Synchronization State Machine

| Status | Description |
| :--- | :--- |
| `SYNCED` | Local record matches the latest known state on the central server |
| `DIRTY_CREATED` | Created offline in SQLite; awaiting initial push to PostgreSQL. |
| `DIRTY_UPDATED` | Modified offline (order header or line items); queued for server update. |
| `DIRTY_DELETED` | Soft-deleted locally while offline; purged from SQLite once confirmed by server. |
| `CONFLICT` | Server rejected push due to version mismatch (`serverVersion > clientVersion`); awaiting user resolution. |

## Getting Started

### Prerequisites
- **Java 21+**
- **Maven 3.9+**
- **Docker & Docker Compose**

### 1. Start the Backend & Database
Launch PostgreSQL and the Spring Boot synchronization server using Docker Compose:

```
docker compose up -d --build
```

The server API will be available at `http://localhost:8080` (`POST /api/sync/push` and `GET /api/sync/pull`).

### 2. Run the Desktop Client
Build the multi-module project and launch the JavaFX client:

```
mvn clean install
mvn -pl desktop-client javafx:run
```

*(You can also run `Launcher.java` directly from your IDE).*

## Testing

Run unit tests (DAO & SyncCoordinator) and server integration tests:

```
mvn test
```