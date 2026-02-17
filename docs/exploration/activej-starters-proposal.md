# Exploration : Starters pour ActiveJ (à la Spring Boot)

## 1. Contexte et objectif

Spring Boot a popularisé le concept de **starters** : des dépendances Maven/Gradle qui, par leur simple inclusion dans un projet, apportent automatiquement :
1. **L'agrégation de dépendances** (un seul artifact à déclarer)
2. **L'auto-configuration** (des beans créés automatiquement avec des défauts sensés)
3. **Des propriétés par défaut** (convention over configuration)

L'objectif est d'explorer comment appliquer ce concept à ActiveJ.

---

## 2. État actuel d'ActiveJ

### 2.1 Architecture modulaire existante

ActiveJ est déjà fortement modulaire avec ~30 modules Maven :

```
core-inject          # DI léger avec @Provides, scopes, generators
core-http            # Serveur/client HTTP haute performance
core-eventloop       # Boucle événementielle non-bloquante
core-promise         # Promesses async
core-csp             # Channels (Communicating Sequential Processes)
core-datastream      # Streaming
core-serializer      # Sérialisation
boot-servicegraph    # Graphe de dépendances de services
boot-config          # Système de configuration
boot-workers         # Worker pools
boot-jmx             # Monitoring JMX
boot-triggers        # Health checks
launcher             # Cycle de vie applicatif
launchers/http       # Launcher préconfigurés HTTP
launchers/rpc        # Launcher préconfigurés RPC
cloud-rpc            # RPC distribué
cloud-fs             # Système de fichiers distribué
```

### 2.2 Système de DI (core-inject)

Le DI d'ActiveJ fonctionne par **composition de modules** :

```java
// Définition d'un module
public class MyModule extends AbstractModule {
    @Provides
    NioReactor reactor() { return Eventloop.create(); }

    @Provides
    HttpServer server(NioReactor reactor, AsyncServlet servlet) {
        return HttpServer.builder(reactor, servlet).build();
    }
}

// Composition
Module combined = Modules.combine(
    ServiceGraphModule.create(),
    ConfigModule.builder().build(),
    new MyModule()
);
Injector injector = Injector.of(combined);
```

Caractéristiques clés :
- **Pas de classpath scanning** automatique (contrairement à Spring)
- **Pas de conditionalité** (@ConditionalOnClass, @ConditionalOnMissingBean n'existent pas)
- **Composition explicite** via `Modules.combine()` et `Modules.override()`
- **BindingGenerator** pour générer des bindings à la demande (pattern-based)
- **BindingTransformer** pour modifier les bindings existants

### 2.3 Les Launchers existants

Les `Launcher` sont les plus proches des starters actuellement :

```java
// HttpServerLauncher fournit déjà :
// - NioReactor (Eventloop)
// - HttpServer configuré
// - ServiceGraphModule
// - ConfigModule
// - Chargement de http-server.properties
public class MyApp extends HttpServerLauncher {
    @Provides
    AsyncServlet servlet() {
        return request -> HttpResponse.ok200()
            .withPlainText("Hello!")
            .toPromise();
    }
}
```

Le `MultithreadedHttpServerLauncher` ajoute en plus :
- `WorkerPoolModule` avec workers configurables
- `PrimaryServer` + `@Worker HttpServer` par thread
- Configuration du nombre de workers

### 2.4 Les Initializers

La classe `Initializers` fournit des configurations par défaut :

```java
// Applique des defaults depuis Config
ofEventloop(config)        // fatalErrorHandler, idleInterval, threadPriority
ofHttpServer(config)       // listenAddresses, keepAlive, readWriteTimeout
ofPrimaryServer(config)    // listenAddresses, socketSettings
ofTaskScheduler(config)    // schedule, retryPolicy, initialDelay
```

### 2.5 Les Archetypes Maven

3 archetypes existent pour scaffolder des projets :
- `archetype-http` → génère un projet HttpServerLauncher
- `archetype-rpc` → génère un projet RPC serveur/client
- `archetype-jdbc` → génère un projet avec DataSource H2

---

## 3. Comparaison Spring Boot Starters vs ActiveJ

| Aspect | Spring Boot | ActiveJ actuel |
|--------|-------------|----------------|
| **Déclaration** | 1 dépendance Maven (`spring-boot-starter-web`) | Multiples dépendances explicites |
| **Auto-configuration** | `@AutoConfiguration` + `@Conditional*` | Aucune — assemblage manuel |
| **Découverte** | `META-INF/spring/AutoConfiguration.imports` | Aucune — modules listés dans `getModule()` |
| **Defaults** | `application.properties` sensés | Config dans le code des Launchers |
| **Overrides** | `@ConditionalOnMissingBean` / properties | `Modules.override()` explicite |
| **Composition** | Implicite par présence sur classpath | Explicite par `Modules.combine()` |
| **Lifecycle** | `SmartLifecycle`, `@PostConstruct` | `ServiceGraph` + `Launcher` |

### Écarts majeurs

1. **Pas de mécanisme de découverte automatique de modules** : en Spring Boot, les auto-configurations sont découvertes via SPI (`META-INF/spring/...`). ActiveJ n'a rien d'équivalent.

2. **Pas de bindings conditionnels** : Spring Boot utilise massivement `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`. ActiveJ n'a pas de mécanisme de conditionalité.

3. **Héritage vs composition** : les Launchers utilisent l'héritage (`extends HttpServerLauncher`), ce qui est limitant (héritage simple Java). Spring Boot utilise la composition (auto-configurations indépendantes).

---

## 4. Proposition d'architecture de starters

### 4.1 Vue d'ensemble

```
┌─────────────────────────────────────────────────────┐
│  Application utilisateur                             │
│                                                      │
│  public class MyApp extends Launcher {               │
│    @Provides AsyncServlet servlet() { ... }          │
│    protected Module getModule() {                    │
│      return ActiveJ.starter()                        │
│             .withHttp()                              │
│             .withJmx()                               │
│             .build();                                │
│    }                                                 │
│  }                                                   │
└────────────┬────────────────────────────────────────┘
             │ compose
┌────────────▼────────────────────────────────────────┐
│  activej-starter-http         (Starter module)       │
│  ├── HttpAutoConfigModule     (auto-config)          │
│  ├── default config values    (convention)           │
│  └── dépendances transitives  (agrégation)           │
└─────────────────────────────────────────────────────┘
```

### 4.2 Trois approches possibles

#### Approche A : Starters comme modules composables (Recommandée)

Rester dans la philosophie ActiveJ : **composition explicite**, pas de magie.

```java
// Nouveau : activej-starter-http
public class HttpStarterModule extends AbstractModule {
    private HttpStarterModule() {}

    public static Builder builder() { return new Builder(); }
    public static Module create() { return builder().build(); }

    public static class Builder {
        private boolean multithreaded = false;
        private int workers = 4;
        private boolean jmx = false;
        private boolean triggers = false;

        public Builder withMultithreading(int workers) {
            this.multithreaded = true;
            this.workers = workers;
            return this;
        }

        public Builder withJmx() { this.jmx = true; return this; }
        public Builder withTriggers() { this.triggers = true; return this; }

        public Module build() {
            List<Module> modules = new ArrayList<>();
            modules.add(ServiceGraphModule.create());
            modules.add(ConfigModule.builder()
                .withEffectiveConfigLogger().build());
            modules.add(new HttpCoreModule(multithreaded, workers));

            if (jmx) modules.add(JmxModule.builder().build());
            if (triggers) modules.add(TriggersModule.create());

            return Modules.combine(modules);
        }
    }
}

// Utilisation
public class MyApp extends Launcher {
    @Provides
    AsyncServlet servlet() {
        return request -> HttpResponse.ok200()
            .withPlainText("Hello!").toPromise();
    }

    @Override
    protected Module getModule() {
        return HttpStarterModule.builder()
            .withMultithreading(8)
            .withJmx()
            .build();
    }

    @Override
    protected void run() throws Exception {
        awaitShutdown();
    }
}
```

**Avantages :**
- Reste dans l'esprit ActiveJ (explicite, zero-magic)
- Pas de modification du core-inject
- Facile à comprendre et débugger
- API fluide avec type safety

**Inconvénients :**
- Pas d'auto-configuration "magique"
- L'utilisateur doit savoir quels starters combiner

---

#### Approche B : Auto-découverte via SPI (ServiceLoader)

Introduire un mécanisme de découverte automatique des modules.

```java
// Nouvelle interface dans core-inject
public interface AutoConfigModule {
    /** Priorité d'application (plus haut = plus tard) */
    int order();

    /** Condition d'activation */
    boolean shouldActivate(AutoConfigContext context);

    /** Le module à installer */
    Module module();
}

// Interface de contexte pour les conditions
public interface AutoConfigContext {
    boolean isClassPresent(String className);
    boolean hasBinding(Key<?> key);
    Config getConfig();
}

// Implémentation dans activej-starter-http
public class HttpAutoConfig implements AutoConfigModule {
    @Override
    public int order() { return 100; }

    @Override
    public boolean shouldActivate(AutoConfigContext ctx) {
        return ctx.isClassPresent("io.activej.http.HttpServer");
    }

    @Override
    public Module module() {
        return new AbstractModule() {
            @Provides
            NioReactor reactor(Config config,
                               OptionalDependency<ThrottlingController> tc) {
                return Eventloop.builder()
                    .initialize(ofEventloop(config.getChild("eventloop")))
                    .withInspector(tc.orElse(null))
                    .build();
            }

            @Provides
            HttpServer server(NioReactor reactor,
                              AsyncServlet servlet, Config config) {
                return HttpServer.builder(reactor, servlet)
                    .initialize(ofHttpServer(config.getChild("http")))
                    .build();
            }
        };
    }
}

// Déclaré dans META-INF/services/io.activej.inject.autoconfig.AutoConfigModule
// io.activej.starter.http.HttpAutoConfig

// Utilisation
public class MyApp extends Launcher {
    @Provides
    AsyncServlet servlet() {
        return request -> HttpResponse.ok200()
            .withPlainText("Hello!").toPromise();
    }

    @Override
    protected Module getModule() {
        return AutoConfigModules.discover(); // Scanne le classpath
    }
}
```

**Avantages :**
- Expérience proche de Spring Boot
- L'ajout d'une dépendance suffit pour activer une fonctionnalité
- Extensible par des tiers

**Inconvénients :**
- Ajoute de la "magie" (contraire à la philosophie ActiveJ)
- Plus difficile à débugger
- Nécessite des modifications dans core-inject
- Coût au démarrage pour le scanning

---

#### Approche C : Hybride (BindingGenerator enrichi)

Exploiter le `BindingGenerator` existant pour fournir des defaults automatiques.

```java
// Enrichir le BindingGenerator existant
public class StarterBindingGenerator implements BindingGenerator<Object> {
    @Override
    public @Nullable Binding<Object> generate(
            BindingLocator bindings, Scope[] scope, Key<Object> key) {

        // Si quelqu'un demande un HttpServer et qu'aucun n'est fourni,
        // en générer un automatiquement
        if (key.getType().equals(HttpServer.class)) {
            return Binding.to(args -> {
                NioReactor reactor = (NioReactor) args[0];
                AsyncServlet servlet = (AsyncServlet) args[1];
                Config config = (Config) args[2];
                return HttpServer.builder(reactor, servlet)
                    .initialize(ofHttpServer(config.getChild("http")))
                    .build();
            }, Key.of(NioReactor.class),
               Key.of(AsyncServlet.class),
               Key.of(Config.class));
        }
        return null;
    }
}
```

**Avantages :**
- Utilise les mécanismes existants d'ActiveJ
- Pas de nouveau framework
- Reste lazy (génère uniquement quand demandé)

**Inconvénients :**
- Limité dans la portée (pas de configuration de haut niveau)
- Complexe à maintenir pour beaucoup de types
- Pas d'agrégation de dépendances Maven

---

### 4.3 Catalogue complet des starters proposés

En suivant l'**Approche A** (recommandée), voici le catalogue exhaustif des starters envisagés, organisé par catégorie.

#### Starters principaux (Core)

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter` | **BOM** — gestion centralisée des versions | `<dependencyManagement>` aligné | Toutes les versions |
| `activej-starter-http` | Serveur HTTP single/multi-thread | HttpServer, RoutingServlet, Config, ServiceGraph, ReactorModule | core-http, boot-servicegraph, boot-config, launchers-common |
| `activej-starter-rpc` | Serveur/client RPC haute performance | RpcServer, RpcClient, stratégies d'envoi (round-robin, sharding, rendezvous hash), Config | cloud-rpc, boot-servicegraph, boot-config |
| `activej-starter-fs` | Serveur de fichiers distribué | FileSystem (local/remote/cluster), opérations CRUD fichiers, Config | cloud-fs, boot-servicegraph, boot-config |

#### Starters monitoring & opérations

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-monitoring` | Monitoring JMX + health checks | JmxModule (MBeans auto-générés, métriques par worker), TriggersModule (severités DEBUG→DISASTER, suppression), stats (ValueStats, EventStats, ExceptionStats, histogrammes) | boot-jmx, boot-jmx-api, boot-jmx-stats, boot-triggers |

**Détail du module monitoring :**
```java
MonitoringStarterModule.builder()
    .withJmx()                          // MBeans auto-générés pour tous les services
    .withTriggers()                     // Health checks avec niveaux de sévérité
    .withGlobalEventloopStats()         // Métriques agrégées des Eventloops
    .withBusinessLogicTriggers()        // Alertes sur temps de traitement
    .withThrottlingTriggers()           // Alertes sur throttling
    .build()
```

#### Starters sécurité & authentification

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-security` | Authentification & sessions HTTP | BasicAuthServlet, SessionServlet, InMemorySessionStore, SslTcpSocket (TLS), HTTPS support | core-http (déjà existant dans core-http, ce starter configure et simplifie) |

**Ce qui existe déjà dans ActiveJ pour la sécurité :**
- `BasicAuthServlet` — authentification HTTP Basic avec lookup de credentials configurable
- `SessionServlet` — gestion de sessions avec `ISessionStore` pluggable
- `InMemorySessionStore` — store de sessions en mémoire
- `SslTcpSocket` — support TLS/SSL pour les connexions TCP
- Support HTTPS natif dans HttpServer et HttpClient

**Ce que le starter apporterait :**
```java
SecurityStarterModule.builder()
    .withBasicAuth(credentials -> ...)       // Auth HTTP Basic
    .withSessions()                          // Sessions avec store in-memory
    .withTls(sslContext)                     // TLS pour le serveur
    .build()

// Exemple d'utilisation
public class SecureApp extends Launcher {
    @Provides
    AsyncServlet servlet(SessionServlet sessionServlet) {
        return RoutingServlet.builder()
            .with("/login", BasicAuthServlet.builder(
                    loginServlet,
                    credentials -> checkDb(credentials))
                .build())
            .with("/app/*", sessionServlet)
            .build();
    }

    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.create(),
            SecurityStarterModule.builder()
                .withSessions()
                .withTls(loadSslContext())
                .build()
        );
    }
}
```

> **Note** : ActiveJ ne fournit pas nativement OAuth2, JWT, ou RBAC. Le starter sécurité se limiterait à ce qui existe (Basic Auth, Sessions, TLS). Un `activej-starter-security-jwt` nécessiterait une dépendance externe.

#### Starters données & stockage

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-redis` | Client Redis async | RedisClient (RESPv2), commandes extensibles | extra/cloud-redis |
| `activej-starter-serializer` | Sérialisation haute performance | Sérialiseurs bytecode-engineered, format binaire compact, gestion de compatibilité | core-serializer, core-codegen |
| `activej-starter-dataflow` | Traitement batch distribué | Datasets multi-partition, SQL (Apache Calcite), JDBC driver | extra/cloud-dataflow |
| `activej-starter-olap` | Base OLAP (cube) | LSM-Tree tables, dimensions/mesures pré-agrégées, requêtes OLAP | extra/cloud-lsmt-cube |

#### Starters distribués & collaboration

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-crdt` | Données distribuées sans conflit | Types CRDT, clustering, repartitioning, discovery services | extra/cloud-crdt |
| `activej-starter-ot` | Transformation opérationnelle | Systèmes collaboratifs (type Google Docs), opérations JSON | extra/cloud-ot |
| `activej-starter-etl` | Extract-Transform-Load | Pipeline ETL, intégration multilog | extra/cloud-etl |

#### Starters streaming & communication

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-streaming` | Streaming réactif | ChannelSupplier/Consumer (CSP), DataStream, rate limiting, compression LZ4, frame encoding | core-csp, core-datastream |
| `activej-starter-websocket` | WebSocket | WebSocketServlet, upgrade HTTP→WS, frames binaires/texte | core-http (sous-ensemble) |

#### Starters scheduling & lifecycle

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-scheduler` | Planification de tâches | TaskScheduler (interval, period, delay), RetryPolicy, métriques JMX | core-promise, boot-jmx-stats |

**Détail du scheduler :**
```java
SchedulerStarterModule.builder()
    .withTask("cleanup", () -> cleanupOldData())
        .every(Duration.ofHours(1))
        .withRetryPolicy(RetryPolicy.exponentialBackoff(
            Duration.ofSeconds(1), Duration.ofMinutes(5)))
    .withTask("healthPing", () -> pingExternal())
        .every(Duration.ofSeconds(30))
    .build()
```

#### Starter test

| Starter | Description | Ce qu'il fournit | Dépendances agrégées |
|---------|-------------|------------------|----------------------|
| `activej-starter-test` | Utilitaires de test | ActiveJRunner (JUnit), EventloopRule, ByteBufRule, UseModules, TestUtils, SQL test utils | test |

### 4.4 Vue d'ensemble par priorité d'implémentation

```
Priorité 1 (Fondations) :
  ├── activej-starter          (BOM)
  ├── activej-starter-http     (serveur web)
  ├── activej-starter-rpc      (RPC)
  └── activej-starter-test     (tests)

Priorité 2 (Production-ready) :
  ├── activej-starter-monitoring    (JMX + triggers)
  ├── activej-starter-security      (auth + sessions + TLS)
  ├── activej-starter-scheduler     (tâches planifiées)
  └── activej-starter-fs            (fichiers distribués)

Priorité 3 (Données) :
  ├── activej-starter-redis         (cache/store)
  ├── activej-starter-serializer    (sérialisation binaire)
  └── activej-starter-streaming     (CSP + datastream)

Priorité 4 (Distribué avancé) :
  ├── activej-starter-crdt          (données distribuées)
  ├── activej-starter-dataflow      (batch processing)
  ├── activej-starter-olap          (OLAP cube)
  ├── activej-starter-ot            (collaboration)
  ├── activej-starter-etl           (ETL pipelines)
  └── activej-starter-websocket     (WebSocket)
```

### 4.5 Structure Maven proposée

```
activej/
├── starters/
│   ├── pom.xml                              # Parent POM des starters
│   │
│   ├── activej-starter/                     # BOM (Bill of Materials)
│   │   └── pom.xml
│   │
│   │── Priorité 1 : Fondations ─────────────────────────────
│   ├── activej-starter-http/                # Serveur HTTP (single + multi-thread)
│   │   ├── pom.xml
│   │   └── src/.../HttpStarterModule.java
│   ├── activej-starter-rpc/                 # RPC client/serveur
│   │   ├── pom.xml
│   │   └── src/.../RpcStarterModule.java
│   ├── activej-starter-test/                # Utilitaires de test
│   │   ├── pom.xml
│   │   └── src/.../TestStarterModule.java
│   │
│   │── Priorité 2 : Production ─────────────────────────────
│   ├── activej-starter-monitoring/          # JMX + Triggers + Stats
│   │   ├── pom.xml
│   │   └── src/.../MonitoringStarterModule.java
│   ├── activej-starter-security/            # Auth + Sessions + TLS
│   │   ├── pom.xml
│   │   └── src/.../SecurityStarterModule.java
│   ├── activej-starter-scheduler/           # Tâches planifiées
│   │   ├── pom.xml
│   │   └── src/.../SchedulerStarterModule.java
│   ├── activej-starter-fs/                  # Fichiers distribués
│   │   ├── pom.xml
│   │   └── src/.../FsStarterModule.java
│   │
│   │── Priorité 3 : Données ────────────────────────────────
│   ├── activej-starter-redis/               # Client Redis async
│   │   ├── pom.xml
│   │   └── src/.../RedisStarterModule.java
│   ├── activej-starter-serializer/          # Sérialisation binaire
│   │   ├── pom.xml
│   │   └── src/.../SerializerStarterModule.java
│   ├── activej-starter-streaming/           # CSP + Datastream
│   │   ├── pom.xml
│   │   └── src/.../StreamingStarterModule.java
│   │
│   │── Priorité 4 : Distribué avancé ──────────────────────
│   ├── activej-starter-crdt/                # CRDT distribué
│   ├── activej-starter-dataflow/            # Batch processing
│   ├── activej-starter-olap/               # OLAP cube
│   ├── activej-starter-ot/                  # Operational transformation
│   ├── activej-starter-etl/                 # ETL pipelines
│   └── activej-starter-websocket/           # WebSocket dédié
```

---

## 5. Modèle de threading et starters

### 5.1 Rappel : le modèle de threading d'ActiveJ

Dans ActiveJ, le threading repose sur l'`Eventloop` (implémentation de `NioReactor`) :

- **1 Eventloop = 1 Thread** : chaque Eventloop possède exactement un thread dédié qui exécute une boucle infinie (`select()` → I/O → tasks → repeat)
- **Single-threaded par design** : à l'intérieur d'un Eventloop, tout est single-threaded (pas de locks, pas de synchronisation)
- **Communication inter-threads** : via `ConcurrentLinkedQueue` (méthode `reactor.execute()`) pour soumettre des tâches d'un thread à un autre

```
Eventloop.run()
   └── while (isAlive) {
           selector.select()           // attente I/O
           processSelectedKeys()       // traiter les événements réseau
           executeConcurrentTasks()     // tâches soumises depuis d'autres threads
           executeLocalTasks()          // tâches internes
           executeScheduledTasks()      // tâches planifiées
       }
```

### 5.2 Les modes de threading existants

**Mode single-thread** (ex: `HttpServerLauncher`) :
```
┌─────────────────────────────────────┐
│ 1 Eventloop (1 thread)              │
│   └── HttpServer + AsyncServlet     │
└─────────────────────────────────────┘
```
- Un seul `@Provides NioReactor` non qualifié
- Tous les services partagent le même Eventloop

**Mode multi-thread** (ex: `MultithreadedHttpServerLauncher`) :
```
┌─────────────────────────────────────────────────┐
│ Primary Eventloop (thread 0)                     │
│   └── PrimaryServer (accepte les connexions)     │
│        ↓ round-robin                             │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│ │ Worker 0 │ │ Worker 1 │ │ Worker 2 │  ...      │
│ │ Eventloop│ │ Eventloop│ │ Eventloop│           │
│ │ (thread) │ │ (thread) │ │ (thread) │           │
│ │ HttpSrv  │ │ HttpSrv  │ │ HttpSrv  │           │
│ └──────────┘ └──────────┘ └──────────┘          │
└─────────────────────────────────────────────────┘
```
- `@Provides NioReactor` non qualifié → reactor primaire
- `@Provides @Worker NioReactor` → un reactor par worker (scope DI isolé)
- Chaque `WorkerPool.enterScope()` crée un `Injector` séparé avec ses propres instances

### 5.3 Le problème : composition de starters et conflits de binding

Quand on combine `HttpStarterModule` + `RpcStarterModule`, les deux veulent fournir un `NioReactor` :

```java
// HttpStarterModule
@Provides NioReactor reactor() { return Eventloop.create(); }

// RpcStarterModule
@Provides NioReactor reactor() { return Eventloop.create(); }

// → CONFLIT DI : deux bindings pour Key<NioReactor>
```

Ce conflit n'existe pas aujourd'hui car les Launchers utilisent l'héritage (un seul Launcher actif à la fois).

### 5.4 Stratégies de threading pour les starters

#### Stratégie 1 : Reactor partagé (recommandée par défaut)

**Principe** : extraire le `NioReactor` dans un module dédié, les starters le consomment sans le fournir.

```
┌─────────────────────────────────────────────┐
│ 1 Eventloop partagé (1 thread)              │
│   ├── HttpServer  (fourni par HTTP starter) │
│   └── RpcServer   (fourni par RPC starter)  │
└─────────────────────────────────────────────┘
```

```java
// ReactorModule — fournit LE reactor unique
public class ReactorModule extends AbstractModule {
    @Provides
    NioReactor reactor(Config config,
                       OptionalDependency<ThrottlingController> tc) {
        return Eventloop.builder()
            .initialize(ofEventloop(config.getChild("eventloop")))
            .withInspector(tc.orElse(null))
            .build();
    }
}

// HttpStarterModule — consomme le reactor, ne le fournit PAS
public class HttpStarterModule extends AbstractModule {
    @Provides
    HttpServer server(NioReactor reactor, AsyncServlet servlet, Config config) {
        return HttpServer.builder(reactor, servlet)
            .initialize(ofHttpServer(config.getChild("http")))
            .build();
    }
}

// RpcStarterModule — consomme le reactor, ne le fournit PAS
public class RpcStarterModule extends AbstractModule {
    @Provides
    RpcServer rpcServer(NioReactor reactor, Config config) {
        return RpcServer.builder(reactor)
            .initialize(ofRpcServer(config.getChild("rpc")))
            .build();
    }
}

// Composition — pas de conflit
Module app = Modules.combine(
    ReactorModule.create(),        // 1 seul reactor
    HttpStarterModule.create(),    // consomme le reactor
    RpcStarterModule.create()      // consomme le même reactor
);
```

**Avantages** :
- Cohérent avec le modèle event-loop unique d'ActiveJ
- Zéro contention (single-threaded)
- Simple à comprendre et débugger
- Performance maximale pour des workloads I/O-bound non-bloquants

**Inconvénients** :
- Un handler bloquant dans un starter bloque tous les autres
- Pas d'isolation en cas de charge asymétrique (HTTP saturé, RPC idle)

#### Stratégie 2 : Reactors isolés par qualifieur (1 thread par starter)

**Principe** : chaque starter possède son propre Eventloop via un qualifieur DI.

```
┌──────────────────────────┐  ┌──────────────────────────┐
│ Eventloop @Http (thread 1)│  │ Eventloop @Rpc (thread 2) │
│   └── HttpServer          │  │   └── RpcServer           │
└──────────────────────────┘  └──────────────────────────┘
```

```java
// Annotations qualifieur
@Qualifier @Retention(RUNTIME) public @interface Http {}
@Qualifier @Retention(RUNTIME) public @interface Rpc {}

// HttpStarterModule
public class HttpStarterModule extends AbstractModule {
    @Provides
    @Http
    NioReactor httpReactor(Config config) {
        return Eventloop.builder()
            .initialize(ofEventloop(config.getChild("eventloop.http")))
            .build();
    }

    @Provides
    HttpServer server(@Http NioReactor reactor, AsyncServlet servlet, Config config) {
        return HttpServer.builder(reactor, servlet)
            .initialize(ofHttpServer(config.getChild("http")))
            .build();
    }
}

// RpcStarterModule
public class RpcStarterModule extends AbstractModule {
    @Provides
    @Rpc
    NioReactor rpcReactor(Config config) {
        return Eventloop.builder()
            .initialize(ofEventloop(config.getChild("eventloop.rpc")))
            .build();
    }

    @Provides
    RpcServer rpcServer(@Rpc NioReactor reactor, Config config) {
        return RpcServer.builder(reactor)
            .initialize(ofRpcServer(config.getChild("rpc")))
            .build();
    }
}
```

**Avantages** :
- Isolation totale : la charge HTTP n'impacte pas le RPC
- Configuration indépendante (`eventloop.http.*` vs `eventloop.rpc.*`)
- Utile pour des workloads mixtes (I/O-bound + CPU-bound)

**Inconvénients** :
- Plus de threads = plus de ressources
- Qualifieurs partout dans le code utilisateur
- La communication entre starters nécessite `reactor.execute()` (cross-thread)

#### Stratégie 3 : Reactor commun + Worker pools indépendants (avancée)

**Principe** : un reactor primaire partagé accepte les connexions, chaque starter a son propre pool de workers.

```
┌────────────────────────────────────────────────────┐
│ Primary Eventloop (thread 0) — accepte tout        │
│    ↓ round-robin HTTP        ↓ round-robin RPC     │
│ ┌────────────────┐        ┌────────────────┐       │
│ │ HTTP Workers   │        │ RPC Workers    │       │
│ │ ┌──────┐┌────┐│        │ ┌──────┐┌────┐ │       │
│ │ │ EL#1 ││EL#2││        │ │ EL#3 ││EL#4│ │       │
│ │ │ Http ││Http││        │ │ Rpc  ││Rpc │ │       │
│ │ └──────┘└────┘│        │ └──────┘└────┘ │       │
│ └────────────────┘        └────────────────┘       │
└────────────────────────────────────────────────────┘
```

```java
Module app = Modules.combine(
    ReactorModule.create(),                     // reactor primaire partagé
    HttpStarterModule.builder()
        .withWorkers(4)                         // 4 threads dédiés HTTP
        .build(),
    RpcStarterModule.builder()
        .withWorkers(2)                         // 2 threads dédiés RPC
        .build()
);
```

Ceci exploite le mécanisme existant de `WorkerPool` d'ActiveJ :
- `WorkerPools.createPool(scope, size)` supporte déjà la création de pools multiples
- Chaque pool crée N scopes DI isolés via `Injector.enterScope()`
- Chaque scope a son propre `Eventloop` (= thread)
- Le `PrimaryServer` distribue les connexions en round-robin vers les workers

**Avantages** :
- Maximum de performance et d'isolation
- Scalabilité indépendante par protocole
- Pattern éprouvé dans ActiveJ (MultithreadedHttpServerLauncher)

**Inconvénients** :
- Complexité accrue (scopes multiples, communication inter-threads)
- Plus de threads = plus de ressources mémoire
- Nécessite une attention aux scopes DI lors de la composition

### 5.5 Recommandation : stratégie progressive

La recommandation est une approche **progressive en 3 niveaux** :

| Niveau | Mode | Threads | Cas d'usage |
|--------|------|---------|-------------|
| **1. Simple** | Reactor partagé | 1 | Prototypage, faible charge, apps mono-protocole |
| **2. Isolé** | Reactors qualifiés | 1 par starter | Charge mixte, isolation nécessaire |
| **3. Scalable** | Workers indépendants | N par starter | Production haute charge |

L'API du builder permet cette progression sans changer d'architecture :

```java
// Niveau 1 : tout sur 1 thread (défaut)
HttpStarterModule.create()

// Niveau 2 : reactor isolé
HttpStarterModule.builder()
    .withDedicatedReactor()
    .build()

// Niveau 3 : worker pool dédié
HttpStarterModule.builder()
    .withWorkers(8)
    .build()
```

Le `ReactorModule` partagé fournit le reactor par défaut. Quand un starter est configuré avec `.withDedicatedReactor()` ou `.withWorkers(n)`, il fournit son propre reactor qualifié et ne dépend plus du reactor partagé.

### 5.6 Impact sur l'architecture des starters

Ce modèle de threading influence la conception des starters :

1. **Les starters ne fournissent PAS de `NioReactor` par défaut** — ils le consomment
2. **Un `ReactorModule` dédié** est le point central de fourniture du reactor
3. **Les options `withDedicatedReactor()` / `withWorkers(n)`** activent l'isolation quand nécessaire
4. **Le `ServiceGraph`** gère automatiquement le démarrage/arrêt dans le bon ordre, quel que soit le nombre de threads
5. **La communication cross-thread** (si starters sur des threads différents) passe par `reactor.execute()`, ce qui est déjà le mécanisme standard d'ActiveJ

---

## 6. Détail de l'implémentation des starters

### 6.1 ReactorModule (module transversal)

Le `ReactorModule` est le module fondamental partagé par tous les starters. Il fournit le `NioReactor` unique par défaut.

```java
package io.activej.starter;

import io.activej.config.Config;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;

import static io.activej.launchers.initializers.Initializers.ofEventloop;

/**
 * Provides a shared {@link NioReactor} (Eventloop) for all starters.
 * <p>
 * By default, all starters share a single Eventloop thread. Individual starters
 * can opt into dedicated reactors via their builder (e.g. {@code .withDedicatedReactor()}).
 */
public final class ReactorModule extends AbstractModule {
    private ReactorModule() {}

    public static ReactorModule create() {
        return new ReactorModule();
    }

    @Provides
    NioReactor reactor(Config config,
                       OptionalDependency<ThrottlingController> throttlingController) {
        return Eventloop.builder()
            .initialize(ofEventloop(config.getChild("eventloop")))
            .withInspector(throttlingController.orElse(null))
            .build();
    }
}
```

### 6.2 POM (`activej-starter-http/pom.xml`)

```xml
<project>
    <parent>
        <groupId>io.activej</groupId>
        <artifactId>activej-starters</artifactId>
        <version>6.0-SNAPSHOT</version>
    </parent>

    <artifactId>activej-starter-http</artifactId>
    <name>ActiveJ : Starter - HTTP</name>

    <dependencies>
        <dependency>
            <groupId>io.activej</groupId>
            <artifactId>activej-http</artifactId>
        </dependency>
        <dependency>
            <groupId>io.activej</groupId>
            <artifactId>activej-launcher</artifactId>
        </dependency>
        <dependency>
            <groupId>io.activej</groupId>
            <artifactId>activej-servicegraph</artifactId>
        </dependency>
        <dependency>
            <groupId>io.activej</groupId>
            <artifactId>activej-config</artifactId>
        </dependency>
        <dependency>
            <groupId>io.activej</groupId>
            <artifactId>activej-launchers-common</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 6.3 Module (`HttpStarterModule.java`)

```java
package io.activej.starter.http;

import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.http.HttpServer;
import io.activej.http.AsyncServlet;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.inject.module.Modules;
import io.activej.net.PrimaryServer;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.starter.ReactorModule;
import io.activej.worker.WorkerPool;
import io.activej.worker.WorkerPoolModule;
import io.activej.worker.WorkerPools;
import io.activej.worker.annotation.Worker;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.*;
import static io.activej.launchers.initializers.Initializers.*;

/**
 * Starter module for HTTP server applications.
 * <p>
 * Provides sensible defaults for:
 * <ul>
 *   <li>{@link HttpServer} configured from properties</li>
 *   <li>{@link Config} with classpath + system property overrides</li>
 *   <li>{@link ServiceGraphModule} for lifecycle management</li>
 *   <li>{@link ReactorModule} for the shared NioReactor (single-thread mode)</li>
 * </ul>
 * <p>
 * <b>Threading modes :</b>
 * <ul>
 *   <li>{@code create()} — single-thread, reactor partagé (1 Eventloop)</li>
 *   <li>{@code builder().withWorkers(n)} — multi-thread, N workers + 1 primary</li>
 * </ul>
 * <p>
 * The user only needs to provide an {@link AsyncServlet} binding.
 *
 * <pre>{@code
 * // Mode simple (1 thread partagé)
 * public class MyApp extends Launcher {
 *     @Provides
 *     AsyncServlet servlet() {
 *         return request -> HttpResponse.ok200()
 *             .withPlainText("Hello!").toPromise();
 *     }
 *
 *     @Override
 *     protected Module getModule() {
 *         return HttpStarterModule.create();
 *     }
 * }
 *
 * // Mode multi-thread (8 workers)
 * public class MyApp extends Launcher {
 *     @Provides @Worker
 *     AsyncServlet servlet(@WorkerId int workerId) {
 *         return request -> HttpResponse.ok200()
 *             .withPlainText("Hello from worker #" + workerId).toPromise();
 *     }
 *
 *     @Override
 *     protected Module getModule() {
 *         return HttpStarterModule.builder().withWorkers(8).build();
 *     }
 * }
 * }</pre>
 */
public final class HttpStarterModule extends AbstractModule {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_PROPERTIES_FILE = "http-server.properties";

    private final String host;
    private final int port;
    private final String propertiesFile;
    private final int workers; // 0 = single-thread (reactor partagé)

    private HttpStarterModule(String host, int port, String propertiesFile, int workers) {
        this.host = host;
        this.port = port;
        this.propertiesFile = propertiesFile;
        this.workers = workers;
    }

    /**
     * Creates a single-threaded HTTP starter.
     * Uses the shared {@link ReactorModule} reactor (1 Eventloop thread).
     */
    public static Module create() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Single-thread mode : consomme le NioReactor fourni par ReactorModule ──

    @Provides
    HttpServer server(NioReactor reactor, AsyncServlet rootServlet, Config config) {
        return HttpServer.builder(reactor, rootServlet)
            .initialize(ofHttpServer(config.getChild("http")))
            .build();
    }

    @Provides
    Config config() {
        return Config.create()
            .with("http.listenAddresses",
                Config.ofValue(ofInetSocketAddress(),
                    new InetSocketAddress(host, port)))
            .overrideWith(ofClassPathProperties(propertiesFile, true))
            .overrideWith(ofSystemProperties("config"));
    }

    @Override
    protected void configure() {
        install(ServiceGraphModule.create());
        install(ConfigModule.builder()
            .withEffectiveConfigLogger()
            .build());
    }

    // ── Module interne pour le mode multi-thread ──

    private static final class HttpMultithreadedModule extends AbstractModule {
        private final String host;
        private final int port;
        private final int workers;
        private final String propertiesFile;

        HttpMultithreadedModule(String host, int port, int workers, String propertiesFile) {
            this.host = host;
            this.port = port;
            this.workers = workers;
            this.propertiesFile = propertiesFile;
        }

        @Provides
        NioReactor primaryReactor(Config config) {
            return Eventloop.builder()
                .initialize(ofEventloop(config.getChild("eventloop.primary")))
                .build();
        }

        @Provides
        @Worker
        NioReactor workerReactor(Config config,
                                 OptionalDependency<ThrottlingController> tc) {
            return Eventloop.builder()
                .initialize(ofEventloop(config.getChild("eventloop.worker")))
                .withInspector(tc.orElse(null))
                .build();
        }

        @Provides
        WorkerPool workerPool(WorkerPools workerPools, Config config) {
            return workerPools.createPool(
                config.get(ofInteger(), "workers", workers));
        }

        @Provides
        PrimaryServer primaryServer(NioReactor primaryReactor,
                                    WorkerPool.Instances<HttpServer> workerServers,
                                    Config config) {
            return PrimaryServer.builder(primaryReactor, workerServers.getList())
                .initialize(ofPrimaryServer(config.getChild("http")))
                .build();
        }

        @Provides
        @Worker
        HttpServer workerServer(NioReactor reactor, AsyncServlet servlet,
                                Config config) {
            return HttpServer.builder(reactor, servlet)
                .initialize(ofHttpWorker(config.getChild("http")))
                .build();
        }

        @Provides
        Config config() {
            return Config.create()
                .with("http.listenAddresses",
                    Config.ofValue(ofInetSocketAddress(),
                        new InetSocketAddress(host, port)))
                .with("workers", "" + workers)
                .overrideWith(ofClassPathProperties(propertiesFile, true))
                .overrideWith(ofSystemProperties("config"));
        }

        @Override
        protected void configure() {
            install(ServiceGraphModule.create());
            install(WorkerPoolModule.create());
            install(ConfigModule.builder()
                .withEffectiveConfigLogger()
                .build());
        }
    }

    // ── Builder ──

    public static final class Builder {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String propertiesFile = DEFAULT_PROPERTIES_FILE;
        private int workers = 0;
        private final List<Module> extraModules = new ArrayList<>();

        private Builder() {}

        public Builder withHost(String host) {
            this.host = host;
            return this;
        }

        public Builder withPort(int port) {
            this.port = port;
            return this;
        }

        public Builder withPropertiesFile(String propertiesFile) {
            this.propertiesFile = propertiesFile;
            return this;
        }

        /**
         * Enables multithreaded mode with N worker threads.
         * Each worker gets its own Eventloop (thread) + HttpServer instance.
         * A primary Eventloop accepts connections and distributes via round-robin.
         * <p>
         * Note: in this mode, the starter provides its OWN reactors
         * and does NOT use the shared ReactorModule.
         */
        public Builder withWorkers(int workers) {
            this.workers = workers;
            return this;
        }

        public Builder withModule(Module module) {
            this.extraModules.add(module);
            return this;
        }

        public Module build() {
            List<Module> modules = new ArrayList<>();

            if (workers > 0) {
                // Multi-thread : le starter fournit ses propres reactors
                modules.add(new HttpMultithreadedModule(host, port, workers, propertiesFile));
            } else {
                // Single-thread : utilise le reactor partagé de ReactorModule
                modules.add(ReactorModule.create());
                modules.add(new HttpStarterModule(host, port, propertiesFile, 0));
            }

            modules.addAll(extraModules);
            return Modules.combine(modules);
        }
    }
}
```

### 6.4 Expérience utilisateur résultante

**Avant (avec Launcher hérité) :**
```java
// Obligation d'hériter — pas possible de combiner HTTP + RPC
public class MyApp extends HttpServerLauncher {
    @Provides
    AsyncServlet servlet() { ... }
}
```

**Après — Niveau 1 : simple, 1 thread partagé (défaut) :**
```java
// HTTP + RPC sur le même Eventloop (1 thread)
public class MyApp extends Launcher {
    @Provides
    AsyncServlet servlet() { ... }

    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.create(),      // consomme le reactor partagé
            RpcStarterModule.create(),       // consomme le même reactor
            MonitoringStarterModule.create()
        );
    }

    @Override
    protected void run() throws Exception { awaitShutdown(); }
}
```

```
Threading résultant :
  1 Eventloop (1 thread) ── HttpServer + RpcServer + JMX
```

**Après — Niveau 2 : multi-thread HTTP, RPC partagé :**
```java
public class MyApp extends Launcher {
    @Provides @Worker
    AsyncServlet servlet(@WorkerId int id) { ... }

    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.builder()
                .withWorkers(4)              // 4 workers HTTP (4 threads)
                .build(),
            RpcStarterModule.create(),       // partage le reactor primaire
            MonitoringStarterModule.create()
        );
    }

    @Override
    protected void run() throws Exception { awaitShutdown(); }
}
```

```
Threading résultant :
  Primary Eventloop (thread 0) ── PrimaryServer (accept) + RpcServer + JMX
    ├── Worker Eventloop (thread 1) ── HttpServer #0
    ├── Worker Eventloop (thread 2) ── HttpServer #1
    ├── Worker Eventloop (thread 3) ── HttpServer #2
    └── Worker Eventloop (thread 4) ── HttpServer #3
```

**Après — Niveau 3 : tout multi-thread :**
```java
public class MyApp extends Launcher {
    @Provides @Worker
    AsyncServlet servlet(@WorkerId int id) { ... }

    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.builder()
                .withWorkers(4)              // 4 workers HTTP
                .build(),
            RpcStarterModule.builder()
                .withWorkers(2)              // 2 workers RPC
                .build(),
            MonitoringStarterModule.create()
        );
    }

    @Override
    protected void run() throws Exception { awaitShutdown(); }
}
```

```
Threading résultant :
  Primary Eventloop (thread 0) ── PrimaryServer (accept HTTP + RPC) + JMX
    ├── HTTP Worker Eventloop (thread 1) ── HttpServer #0
    ├── HTTP Worker Eventloop (thread 2) ── HttpServer #1
    ├── HTTP Worker Eventloop (thread 3) ── HttpServer #2
    ├── HTTP Worker Eventloop (thread 4) ── HttpServer #3
    ├── RPC Worker Eventloop  (thread 5) ── RpcServer #0
    └── RPC Worker Eventloop  (thread 6) ── RpcServer #1
```

---

## 7. BOM (Bill of Materials)

### `activej-starter/pom.xml`

```xml
<project>
    <groupId>io.activej</groupId>
    <artifactId>activej-starter</artifactId>
    <version>6.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>ActiveJ : Starter BOM</name>
    <description>
        Bill of Materials for ActiveJ starters.
        Import this in dependencyManagement to get aligned versions.
    </description>

    <dependencyManagement>
        <dependencies>
            <!-- Starters -->
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-starter-http</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-starter-http-mt</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-starter-rpc</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-starter-monitoring</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-starter-fs</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Core modules (version-aligned) -->
            <dependency>
                <groupId>io.activej</groupId>
                <artifactId>activej-http</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- ... all core modules ... -->
        </dependencies>
    </dependencyManagement>
</project>
```

**Utilisation côté projet utilisateur :**
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

<dependencies>
    <dependency>
        <groupId>io.activej</groupId>
        <artifactId>activej-starter-http</artifactId>
        <!-- version gérée par le BOM -->
    </dependency>
</dependencies>
```

---

## 8. Comparaison des approches

| Critère | A: Modules composables | B: Auto-découverte SPI | C: BindingGenerator |
|---------|------------------------|------------------------|---------------------|
| Fidélité à ActiveJ | ★★★★★ | ★★☆☆☆ | ★★★★☆ |
| DX (Developer Experience) | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| Complexité d'implémentation | ★★★★★ (faible) | ★★☆☆☆ (élevée) | ★★★☆☆ |
| Debugging | ★★★★★ | ★★★☆☆ | ★★★★☆ |
| Composabilité | ★★★★★ | ★★★★☆ | ★★☆☆☆ |
| Extensibilité tiers | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| Modifications du core | Aucune | Significatives | Mineures |
| Performance startup | Nulle impact | Impact scanning | Nulle impact |

### Recommandation : Approche A

L'approche A (modules composables avec builder) est recommandée car elle :
1. **Respecte la philosophie ActiveJ** : explicite, pas de magie, zero overhead
2. **Ne nécessite aucune modification du core** : utilise les mécanismes existants
3. **Est facile à implémenter et tester** : ce sont des modules standards
4. **Résout le problème principal** : l'héritage simple des Launchers
5. **Offre la composition** : on peut combiner HTTP + RPC + monitoring librement

L'approche B (SPI) pourrait être envisagée en **phase 2** si la demande de "zero-config" est forte, mais elle va contre l'ADN d'ActiveJ.

---

## 9. Plan d'implémentation suggéré

### Phase 1 : Fondations
1. Créer le module parent `starters/pom.xml`
2. Créer le BOM `activej-starter`
3. Créer le `ReactorModule` partagé (module transversal de threading)
4. Implémenter `activej-starter-http` (single + multi-thread via builder)
5. Implémenter `activej-starter-rpc`
6. Implémenter `activej-starter-test`

### Phase 2 : Production-ready
7. Implémenter `activej-starter-monitoring` (JMX + Triggers + Stats)
8. Implémenter `activej-starter-security` (BasicAuth + Sessions + TLS)
9. Implémenter `activej-starter-scheduler` (TaskScheduler + RetryPolicy)
10. Implémenter `activej-starter-fs`

### Phase 3 : Données & streaming
11. Implémenter `activej-starter-redis`
12. Implémenter `activej-starter-serializer`
13. Implémenter `activej-starter-streaming` (CSP + Datastream)
14. Implémenter `activej-starter-websocket`

### Phase 4 : Distribué avancé (extra)
15. Implémenter `activej-starter-crdt`
16. Implémenter `activej-starter-dataflow`
17. Implémenter `activej-starter-olap`
18. Implémenter `activej-starter-ot`
19. Implémenter `activej-starter-etl`

### Phase 5 : Outillage & écosystème
20. Mettre à jour les archetypes pour utiliser les starters
21. Mettre à jour les exemples
22. Documentation
23. Migration guide (Launchers → Starters)

### Phase 6 (optionnelle) : Auto-découverte
24. Concevoir l'interface `AutoConfigModule`
25. Implémenter le scanning SPI dans un module optionnel
26. Adapter les starters pour supporter la découverte automatique

---

## 10. Risques et considérations

### Risques
- **Fragmentation** : deux façons de faire la même chose (Launchers existants + Starters)
- **Migration** : les utilisateurs actuels des Launchers devront migrer (ou coexistence)
- **Complexité** : plus de modules Maven à maintenir

### Mitigations
- Les Launchers existants pourraient être dépréciés en faveur des starters
- Les Launchers pourraient utiliser les starters en interne (composition)
- Le BOM simplifie la gestion des versions

### Compatibilité
Les starters peuvent coexister avec les Launchers existants. La migration serait :

```java
// Avant
public class MyApp extends HttpServerLauncher { ... }

// Après
public class MyApp extends Launcher {
    @Override
    protected Module getModule() {
        return HttpStarterModule.create();
    }
    @Override
    protected void run() throws Exception { awaitShutdown(); }
}
```

---

## 11. Conclusion

L'introduction de starters dans ActiveJ est faisable et apporterait une amélioration significative de l'expérience développeur. L'approche recommandée (modules composables avec builder) reste fidèle à la philosophie du framework tout en résolvant la limitation principale de l'héritage simple des Launchers.

Le catalogue identifie **17 starters** répartis en 4 niveaux de priorité, couvrant :
- **Serveurs** : HTTP (single/multi-thread), RPC, FileSystem, WebSocket
- **Opérations** : monitoring (JMX/triggers/stats), scheduling, sécurité (auth/sessions/TLS)
- **Données** : Redis, sérialisation binaire, streaming réactif
- **Distribué** : CRDT, dataflow, OLAP, OT, ETL

Le gain principal est la **composabilité** : aujourd'hui, un `HttpServerLauncher` ne peut pas facilement devenir aussi un serveur RPC ou inclure du monitoring JMX sans réécrire toute la configuration. Avec les starters :

```java
// Application HTTP + RPC + monitoring + scheduling + auth en quelques lignes
Module app = Modules.combine(
    HttpStarterModule.builder().withWorkers(8).build(),
    RpcStarterModule.create(),
    MonitoringStarterModule.create(),
    SchedulerStarterModule.create(),
    SecurityStarterModule.builder().withSessions().withTls(sslCtx).build()
);
```

Le modèle de threading progressif (reactor partagé → reactors isolés → worker pools) permet d'adapter la performance sans changer d'architecture.
