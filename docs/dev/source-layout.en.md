# Source ownership and directory rules

[简体中文](source-layout.md)

Development centers on `common` and `targets`. `common` owns game implementations and shared dependencies; `targets` own independent builds. Source layers select the compilation environment. Java packages classify business responsibilities. A version number is not a feature category, and `compat` is not a miscellaneous directory.

## Where a feature belongs

```mermaid
flowchart TD
    A[Feature] --> B[One implementation in common/src/main]
    B --> C{Actual API difference?}
    C -->|No| D[All targets compile the same sources]
    C -->|Yes| E[Extract a small typed interface]
    E --> F[Implement the necessary calls in compat]
    F --> G[Select implementations in the common build]
    G --> H[Export roots and assign their matching SDK]
```

Keep algorithms, business rules, state, configuration semantics and resource-processing flows in `main`. Adapter layers implement only evidenced API, type and lifecycle differences. Different imports or a few method calls do not justify copying a business class. Loaders on the same version share version adapters; versions using the same Loader share its adapters while the APIs remain applicable.

## Compilation layers

| Directory | Contents | Selected by |
| --- | --- | --- |
| `common/src/main/java` | Shared implementation, interfaces and contracts | All targets |
| `common/src/main/resources` | Shared handwritten resources | All targets |
| `common/src/compat/minecraft/<mc>/java` | Minecraft API differences | Targets using that Minecraft version |
| `common/src/compat/<loader>/java` | Loader entry points and API differences | Targets using that Loader |
| `common/src/compat/<loader>/<mc>/java` | Differences requiring both conditions | That Loader / Minecraft pair |
| `resources` in an adapter layer | Resources or configuration required by its condition | The same targets as the layer |
| `common/src/test` | Shared module test sources and resources | Test source sets, outside the production ownership inventory |
| `targets/<loader>-<mc>` | Build, Wrapper, metadata, run and publishing configuration | One independent Gradle root per pair |
| `targets/<loader>-<mc>/src/generated/resources` | Target DataGen output | Its actual providers and task |

The default build supports the three adapter paths above. To reuse one API implementation across several versions, group an additional layer by domain, capability and API generation, for example `minecraft/registry/holder/java`. Select it explicitly in `common/build.gradle(.kts)`, document its conditions, and include it in the actual `templeSourceDirectories` / `templeResourceDirectories`. Creating a directory does not wire it into the build. Do not copy identical adapters just to fit version folders, or recursively include every `java` directory.

Custom layers must remain under `common/src`. Every nonempty production path must be selected by at least one configured target. Select same-name implementations with mutually exclusive predicates so each target compiles only one. An unassigned branch is not supported. Explain its purpose to the maintainer before wiring, relocating or retiring it; do not add versions, delete sources or assign an arbitrary SDK to make it disappear.

New projects keep only selected Loader adapters in production directories. Unselected Loader files move to `gradle/target-blueprints/common/<loader>` with a `.template` suffix. These source blueprints support later target creation; they are outside Java compilation and IDE source roots. Adding that Loader restores them to `common/src/compat/<loader>` without overwriting files with different contents. Both creation entry points use this policy. Reusing a project to create another also updates Java packages inside its blueprints.

When reusing a project, current targets supply the matching blueprint files; other existing blueprint files are retained. Each output path is copied once. Production Java sources and blueprint sources use the same package conversion.

## Packages follow responsibilities

Paths below are relative to the project's actual Java base package. Create only packages that are needed. A few classes with one responsibility can stay together.

| Responsibility | Example packages | Boundary |
| --- | --- | --- |
| Registration | `registry/item`, `registry/block`, `registry/entity` | Categorized registration entry points, without business implementations |
| Blocks and items | `block/<feature>`, `item/<feature>` | Behavior, components and interactions |
| Entities | `entity/passive`, `entity/hostile`, `entity/boss/<kind>` | Behavior and state; rendering belongs in client |
| Block entities and menus | `blockentity/<feature>`, `menu/<feature>` | Server behavior, storage and synchronization |
| Commands | `command/<feature>`, `command/argument/<type>` | Registration, arguments and execution |
| Networking | `network/payload/<feature>`, `network/handler/<feature>` | Message definitions and handling |
| Configuration and storage | `config/<feature>`, `storage/<data-domain>` | Separate configuration semantics from persistence |
| World | `world/biome`, `world/feature`, `world/structure` | Generation, placement and world behavior |
| Client rendering | `client/renderer/entity`, `client/renderer/blockentity` | Renderers and their registration |
| Client UI | `client/screen/<feature>`, `client/input`, `client/i18n` | Separate screens, input and localization |
| Client presentation | `client/model`, `client/particle`, `client/sound` | Separate models, particles and audio |
| Data generation | `data/recipe`, `data/loot`, `data/tag`, `data/worldgen` | Providers grouped by resource responsibility |
| Mixins | `mixin/<affected-domain>` | Injection points and their configurations |
| Optional mod compatibility | `compat/<dependency>/<feature>` | Distinct from the build layer `src/compat`; isolate optional type loading |

The table is guidance for real responsibilities, not a mandatory empty skeleton. Do not mix unrelated work in the base package, one large `client` package, or `manager`, `util` and `misc` directories. Filename prefixes do not replace packages. Loader entry points, shared initialization and generated `BuildInfo` may keep framework-required locations. Entry points wire components together; they must not grow into combined configuration, networking, rendering and registration implementations.

Adapters retain the same responsibility hierarchy. An interface in `client/screen/config` has its version implementation in `client/screen/config` beneath the selected adapter Java root. Moving classes also updates packages, imports, entry points, registration, Mixin configuration and resource references.

## Resources, dependencies and build code

- Keep handwritten resources in `resources/assets/<modid>/{lang,textures,models,blockstates,...}` and `resources/data/<modid>/...`. Preserve editor assets and existing provider ownership.
- Keep Loader metadata, AT, AW / ClassTweaker and Mixin configuration at their required paths in the matching resource layer. Classify client assets separately from server data.
- Declare shared libraries with `api`, `implementation`, `compileOnly` and `runtimeOnly` in `common/build.gradle(.kts)`. Dependencies specific to one target stay in its configuration.
- Keep build conventions, the IDEA extension, wizard assets and publishing tools in their respective `gradle`, `buildSrc` and `integrations` directories, outside game sources and release JARs.

## Models and ownership

Configuring a target exports its actual roots to `common/.gradle/contexts/<target>/ide-sources.json`. The model also records the editing target, standard build-input digests and production source/resource directory inventory. The extension uses these to detect stale data. Sync all targets after changing build scripts or adding or moving source directories. Older model schemas also require synchronization; installing a new ZIP alone does not refresh project caches.

For missing, stale or invalid models and modules that have not been imported, the extension lists the affected targets and retains the last complete ownership assignment. Once all models are ready, it assigns owners and reports paths used by no target. Each physical directory has one IDE owner; other targets depend on that owner. Its SDK comes from a target that actually uses the directory. Do not mark mutually exclusive implementations as sources under the current common SDK.

The root `verifySourceLayout` task reads existing models and lists each target's Java and resource roots. It fails on stale models, unassigned paths or duplicate Java paths in one target. It does not synchronize projects, build the game or select SDKs. It checks build ownership; judging business responsibilities still requires reading packages, initialization and call chains.

Actual source roots determine file ownership. Java packages may contain `resources`, and asset directories may be named `java`; these names do not change their type. Non-Java files beneath a Java root are not automatically resources and need an actual resource set.

## Completion criteria

Before adding or moving a file, identify its responsibility, target scope, source root and consumers. Completed development or migration requires:

1. One shared implementation per feature; every adapter has an evidenced API difference and a consumer.
2. Every nonempty production directory selected by a target; explicit predicates for custom capability layers and exclusive same-name implementations.
3. Files in their responsibility packages and resource directories; updated entry points, references, registration, access configuration and producer pipelines.
4. Real Gradle models for all targets and common; completion, navigation, release packages and runtime behavior confirmed within their respective acceptance scopes.
5. No parallel `versions`, `loaders` or retired project shells. Establish ownership before cleanup and preserve user data and unrelated changes.

Finish production changes before the agreed acceptance batch. Source migration, model ownership, package classification and game behavior are separate criteria; passing one does not establish the others.
