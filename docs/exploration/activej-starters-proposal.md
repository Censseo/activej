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

### 4.3 Starters proposés

En suivant l'**Approche A** (recommandée), voici les starters envisagés :

| Starter | Contenu | Dépendances agrégées |
|---------|---------|----------------------|
| `activej-starter-http` | Eventloop, HttpServer, Config, ServiceGraph | core-http, boot-servicegraph, boot-config, launchers-common |
| `activej-starter-http-mt` | Idem + WorkerPool, PrimaryServer | idem + boot-workers, core-net |
| `activej-starter-rpc` | RpcServer, Eventloop, Config, ServiceGraph | cloud-rpc, boot-servicegraph, boot-config |
| `activej-starter-monitoring` | JMX, Triggers | boot-jmx, boot-triggers |
| `activej-starter-fs` | FileServer, Eventloop, Config, ServiceGraph | cloud-fs, boot-servicegraph, boot-config |
| `activej-starter` | BOM (Bill of Materials) pour la gestion des versions | Toutes les versions alignées |

### 4.4 Structure Maven proposée

```
activej/
├── starters/
│   ├── pom.xml                          # Parent POM des starters
│   ├── activej-starter/                 # BOM
│   │   └── pom.xml                      # <packaging>pom</packaging>
│   ├── activej-starter-http/
│   │   ├── pom.xml                      # Agrège les dépendances
│   │   └── src/main/java/.../
│   │       └── HttpStarterModule.java   # Module auto-configuré
│   ├── activej-starter-http-mt/
│   │   ├── pom.xml
│   │   └── src/main/java/.../
│   │       └── HttpMtStarterModule.java
│   ├── activej-starter-rpc/
│   │   ├── pom.xml
│   │   └── src/main/java/.../
│   │       └── RpcStarterModule.java
│   ├── activej-starter-monitoring/
│   │   ├── pom.xml
│   │   └── src/main/java/.../
│   │       └── MonitoringStarterModule.java
│   └── activej-starter-fs/
│       ├── pom.xml
│       └── src/main/java/.../
│           └── FsStarterModule.java
```

---

## 5. Détail de l'implémentation du starter HTTP

### 5.1 POM (`activej-starter-http/pom.xml`)

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

### 5.2 Module (`HttpStarterModule.java`)

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
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.launchers.initializers.Initializers.ofEventloop;
import static io.activej.launchers.initializers.Initializers.ofHttpServer;

/**
 * Starter module for HTTP server applications.
 * <p>
 * Provides sensible defaults for:
 * <ul>
 *   <li>{@link NioReactor} (Eventloop)</li>
 *   <li>{@link HttpServer} configured from properties</li>
 *   <li>{@link Config} with classpath + system property overrides</li>
 *   <li>{@link ServiceGraphModule} for lifecycle management</li>
 * </ul>
 * <p>
 * The user only needs to provide an {@link AsyncServlet} binding.
 *
 * <pre>{@code
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
 *
 *     @Override
 *     protected void run() throws Exception {
 *         awaitShutdown();
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

    private HttpStarterModule(String host, int port, String propertiesFile) {
        this.host = host;
        this.port = port;
        this.propertiesFile = propertiesFile;
    }

    public static HttpStarterModule create() {
        return new HttpStarterModule(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_PROPERTIES_FILE);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Provides
    NioReactor reactor(Config config,
                       OptionalDependency<ThrottlingController> throttlingController) {
        return Eventloop.builder()
            .initialize(ofEventloop(config.getChild("eventloop")))
            .withInspector(throttlingController.orElse(null))
            .build();
    }

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

    public static final class Builder {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String propertiesFile = DEFAULT_PROPERTIES_FILE;
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

        public Builder withModule(Module module) {
            this.extraModules.add(module);
            return this;
        }

        public Module build() {
            Module starter = new HttpStarterModule(host, port, propertiesFile);
            if (extraModules.isEmpty()) {
                return starter;
            }
            List<Module> all = new ArrayList<>();
            all.add(starter);
            all.addAll(extraModules);
            return Modules.combine(all);
        }
    }
}
```

### 5.3 Expérience utilisateur résultante

**Avant (avec Launcher hérité) :**
```java
// Obligation d'hériter de HttpServerLauncher
// Pas possible de combiner HTTP + RPC dans un même launcher
public class MyApp extends HttpServerLauncher {
    @Provides
    AsyncServlet servlet() { ... }
}
```

**Après (avec starters composables) :**
```java
// Composition libre — on peut mixer HTTP + monitoring + custom
public class MyApp extends Launcher {
    @Provides
    AsyncServlet servlet() { ... }

    @Override
    protected Module getModule() {
        return Modules.combine(
            HttpStarterModule.create(),
            MonitoringStarterModule.create()
        );
    }

    @Override
    protected void run() throws Exception {
        awaitShutdown();
    }
}
```

---

## 6. BOM (Bill of Materials)

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

## 7. Comparaison des approches

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

## 8. Plan d'implémentation suggéré

### Phase 1 : Fondations
1. Créer le module parent `starters/pom.xml`
2. Créer le BOM `activej-starter`
3. Implémenter `activej-starter-http` (refactoring de `HttpServerLauncher`)
4. Implémenter `activej-starter-http-mt` (refactoring de `MultithreadedHttpServerLauncher`)

### Phase 2 : Extension
5. Implémenter `activej-starter-rpc`
6. Implémenter `activej-starter-monitoring` (JMX + Triggers)
7. Implémenter `activej-starter-fs`

### Phase 3 : Outillage
8. Mettre à jour les archetypes pour utiliser les starters
9. Mettre à jour les exemples
10. Documentation

### Phase 4 (optionnelle) : Auto-découverte
11. Concevoir l'interface `AutoConfigModule`
12. Implémenter le scanning SPI dans un module optionnel
13. Adapter les starters pour supporter la découverte automatique

---

## 9. Risques et considérations

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

## 10. Conclusion

L'introduction de starters dans ActiveJ est faisable et apporterait une amélioration significative de l'expérience développeur. L'approche recommandée (modules composables avec builder) reste fidèle à la philosophie du framework tout en résolvant la limitation principale de l'héritage simple des Launchers.

Le gain principal est la **composabilité** : aujourd'hui, un `HttpServerLauncher` ne peut pas facilement devenir aussi un serveur RPC ou inclure du monitoring JMX sans réécrire toute la configuration. Avec les starters, c'est un simple `Modules.combine(HttpStarterModule.create(), RpcStarterModule.create(), MonitoringStarterModule.create())`.
