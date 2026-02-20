# ActiveJ Starters

Des modules composables pour ActiveJ, inspirés du concept des starters Spring Boot.
Chaque starter encapsule les dépendances, la configuration par défaut et les bindings DI nécessaires pour une fonctionnalité donnée.

## Philosophie

- **Explicite plutôt qu'implicite** : pas de classpath scanning, composition via `Modules.combine()`
- **Builder pattern** : chaque starter expose `create()` (défauts) et `builder()` (personnalisation)
- **Convention over configuration** : des valeurs par défaut sensées, surchargeables via properties ou builder
- **Threading progressif** : du single-thread au worker pool sans changer l'architecture

## Quickstart

### Dépendance Maven (BOM)

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.activej</groupId>
      <artifactId>activej-starter</artifactId>
      <version>6.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Exemple : serveur HTTP minimal

```java
public class MyApp extends Launcher {
    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.create(),
            new AbstractModule() {
                @Provides
                AsyncServlet servlet() {
                    return request -> HttpResponse.ok200()
                        .withPlainText("Hello ActiveJ!")
                        .toPromise();
                }
            }
        );
    }

    public static void main(String[] args) throws Exception {
        new MyApp().launch(args);
    }
}
```

### Exemple : composition de plusieurs starters

```java
Module module = Modules.combine(
    HttpStarterModule.builder()
        .withPort(9090)
        .withWorkers(4)
        .build(),
    MonitoringStarterModule.create(),
    SecurityStarterModule.builder()
        .withSessions("MY_SESSION")
        .build(),
    SchedulerStarterModule.builder()
        .withTask("cleanup", () -> cleanupJob(), Schedule.ofPeriod(Duration.ofHours(1)))
        .build()
);
```

## Catalogue des starters

### Core

| Starter | Artifact | Description |
|---------|----------|-------------|
| [HTTP](#activej-starter-http) | `activej-starter-http` | Serveur HTTP single/multi-thread |
| [RPC](#activej-starter-rpc) | `activej-starter-rpc` | Serveur RPC |
| [Common](#activej-starter-common) | `activej-starter-common` | Modules de base (ReactorModule, BaseStarterModule) |
| [Test](#activej-starter-test) | `activej-starter-test` | NioReactor pour les tests |

### Infrastructure

| Starter | Artifact | Description |
|---------|----------|-------------|
| [Monitoring](#activej-starter-monitoring) | `activej-starter-monitoring` | JMX + health checks (triggers) |
| [Security](#activej-starter-security) | `activej-starter-security` | Sessions in-memory, TLS |
| [Scheduler](#activej-starter-scheduler) | `activej-starter-scheduler` | Tâches périodiques avec retry |

### Data & Streaming

| Starter | Artifact | Description |
|---------|----------|-------------|
| [FS](#activej-starter-fs) | `activej-starter-fs` | Système de fichiers local |
| [Redis](#activej-starter-redis) | `activej-starter-redis` | Client Redis |
| [Serializer](#activej-starter-serializer) | `activej-starter-serializer` | Sérialisation binaire haute performance |
| [Streaming](#activej-starter-streaming) | `activej-starter-streaming` | CSP + Datastream avec Executor |
| [WebSocket](#activej-starter-websocket) | `activej-starter-websocket` | Configuration WebSocket |

### AI

| Starter | Artifact | Description |
|---------|----------|-------------|
| [AI](#activej-starter-ai) | `activej-starter-ai` | Chat clients OpenAI / Anthropic (streaming) |

### Distribué (profil `extra`)

| Starter | Artifact | Statut |
|---------|----------|--------|
| CRDT | `activej-starter-crdt` | Placeholder |
| Dataflow | `activej-starter-dataflow` | Placeholder |
| OLAP | `activej-starter-olap` | Placeholder |
| OT | `activej-starter-ot` | Placeholder |
| ETL | `activej-starter-etl` | Placeholder |

---

## Détail par starter

### activej-starter-common

Modules partagés par les autres starters.

**ReactorModule** : fournit un `NioReactor` (Eventloop) configuré depuis `Config`.

**BaseStarterModule** : installe `ServiceGraphModule`, `ConfigModule` et fournit une `InetSocketAddress` d'écoute.

| Builder | Défaut |
|---------|--------|
| `withHost(String)` | `localhost` |
| `withPort(int)` | `8080` |
| `withPropertiesFile(String)` | `application.properties` |

---

### activej-starter-http

Serveur HTTP avec support single-thread et multi-thread (worker pool).

```java
// Single-thread (défaut)
HttpStarterModule.create()

// Multi-thread avec 4 workers
HttpStarterModule.builder().withWorkers(4).build()
```

| Builder | Défaut |
|---------|--------|
| `withHost(String)` | `localhost` |
| `withPort(int)` | `8080` |
| `withPropertiesFile(String)` | `http-server.properties` |
| `withWorkers(int)` | désactivé (single-thread) |

**Fournit** : `HttpServer` (single-thread) ou `PrimaryServer` + worker `HttpServer` (multi-thread).

---

### activej-starter-rpc

Configuration serveur RPC. L'utilisateur doit fournir son propre `RpcServer`.

| Builder | Défaut |
|---------|--------|
| `withHost(String)` | `localhost` |
| `withPort(int)` | `5353` |
| `withPropertiesFile(String)` | `rpc-server.properties` |

**Config** : `rpc.listenAddresses`

---

### activej-starter-monitoring

Monitoring JMX et health checks (triggers).

```java
// JMX + Triggers (défaut)
MonitoringStarterModule.create()

// JMX uniquement
MonitoringStarterModule.builder().withJmx().build()
```

| Builder | Description |
|---------|-------------|
| `withJmx()` | Active le monitoring JMX |
| `withTriggers()` | Active les health checks |
| `withGlobalEventloopStats()` | MBean global eventloop (requiert JMX) |
| `withBusinessLogicTriggers()` | Triggers métier (requiert Triggers) |
| `withThrottlingTriggers()` | Triggers de throttling (requiert Triggers) |

---

### activej-starter-security

Sessions in-memory et support TLS.

```java
// Sessions activées (défaut)
SecurityStarterModule.create()

// Sessions avec cookie personnalisé + TLS
SecurityStarterModule.builder()
    .withSessions("MY_SESSION")
    .withTls(sslContext)
    .build()
```

| Builder | Défaut |
|---------|--------|
| `withSessions(String)` | `ACTIVEJ_SESSION_ID` |
| `withTls(SSLContext)` | désactivé |

**Fournit** : `ISessionStore<?>` (InMemorySessionStore).

---

### activej-starter-scheduler

Enregistrement de tâches périodiques sous forme de `TaskScheduler`.

```java
SchedulerStarterModule.builder()
    .withTask("cleanup", () -> cleanup(), Schedule.ofPeriod(Duration.ofMinutes(30)))
    .withTask("report", () -> report(), Schedule.ofInterval(Duration.ofHours(1)), retryPolicy)
    .build()
```

| Builder | Description |
|---------|-------------|
| `withTask(name, task, schedule)` | Ajoute une tâche périodique |
| `withTask(name, task, schedule, retryPolicy)` | Avec politique de retry |

**Fournit** : `Set<TaskScheduler>` via `@ProvidesIntoSet`.

---

### activej-starter-fs

Système de fichiers local avec `IFileSystem`.

| Builder | Défaut |
|---------|--------|
| `withPath(Path)` | `./storage` |

| Config | Défaut |
|--------|--------|
| `fs.path` | `./storage` |

**Fournit** : `IFileSystem`, `Executor`.

---

### activej-starter-redis

Client Redis non-bloquant.

| Builder | Défaut |
|---------|--------|
| `withHost(String)` | `localhost` |
| `withPort(int)` | `6379` |

| Config | Défaut |
|--------|--------|
| `redis.host` | `localhost` |
| `redis.port` | `6379` |

**Fournit** : `RedisClient`.

---

### activej-starter-serializer

Sérialisation binaire haute performance via `SerializerFactory`.

| Builder | Défaut |
|---------|--------|
| `withCompatibilityLevel(CompatibilityLevel)` | `LEVEL_4` |

**Fournit** : `SerializerFactory`.

---

### activej-starter-streaming

Ré-exporte les dépendances CSP et Datastream, fournit un `Executor` pour les opérations bloquantes.

```java
StreamingStarterModule.create()
```

**Fournit** : `Executor` (cached thread pool).

---

### activej-starter-websocket

Configuration des messages WebSocket.

| Config | Défaut |
|--------|--------|
| `websocket.maxMessageSize` | `1 MB` |

**Fournit** : `MemSize` pour la taille max des messages.

---

### activej-starter-ai

Clients chat pour OpenAI et Anthropic avec support streaming (SSE).

```java
// OpenAI (défaut)
AiStarterModule.builder()
    .withOpenAi("sk-...")
    .build()

// Anthropic
AiStarterModule.builder()
    .withAnthropic("sk-ant-...")
    .withModel("claude-sonnet-4-5-20250929")
    .build()
```

| Builder | Défaut |
|---------|--------|
| `withOpenAi(String apiKey)` | provider OpenAI |
| `withAnthropic(String apiKey)` | provider Anthropic |
| `withModel(String)` | `gpt-4o` (OpenAI) / `claude-sonnet-4-5-20250929` (Anthropic) |
| `withBaseUrl(String)` | URL officielle du provider |
| `withTemperature(double)` | `0.7` |

**Fournit** : `ChatClient` avec méthodes `chat(request)` et `chatStream(request)`.

**Types** :
- `ChatMessage` : message (system/user/assistant)
- `ChatRequest` : requête avec model, messages, temperature, stream
- `ChatResponse` : réponse avec id, content, usage
- `ChatEvent` : événement de streaming (delta + done)

---

### activej-starter-test

Fournit un `NioReactor` simple pour les tests unitaires.

```java
TestStarterModule.create()
```

---

## Modèle de threading

Les starters supportent trois niveaux de threading, du plus simple au plus scalable :

1. **Reacteur partagé** (défaut) : tous les starters partagent un seul `Eventloop` via `ReactorModule`
2. **Reacteurs isolés** : chaque starter peut avoir son propre reactor via `@Qualifier`
3. **Worker pools** : un reactor primaire + N workers (ex: `HttpStarterModule.builder().withWorkers(4)`)

## Structure Maven

```
starters/
├── pom.xml                          # Parent POM (modules)
├── activej-starter/                 # BOM (Bill of Materials)
├── activej-starter-common/          # ReactorModule, BaseStarterModule
├── activej-starter-http/
├── activej-starter-rpc/
├── activej-starter-monitoring/
├── activej-starter-security/
├── activej-starter-scheduler/
├── activej-starter-fs/
├── activej-starter-redis/
├── activej-starter-serializer/
├── activej-starter-streaming/
├── activej-starter-websocket/
├── activej-starter-ai/
├── activej-starter-test/
├── activej-starter-crdt/            # extra profile
├── activej-starter-dataflow/        # extra profile
├── activej-starter-olap/            # extra profile
├── activej-starter-ot/              # extra profile
└── activej-starter-etl/             # extra profile
```

Les 5 modules distribués (crdt, dataflow, olap, ot, etl) sont dans le profil Maven `extra` et activés avec `-Pextra`.
