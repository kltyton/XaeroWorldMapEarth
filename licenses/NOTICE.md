# Third-party notices

Xaero's World Map: Earth is developed by kltyton and licensed under MIT. Its
license does not replace the licenses of bundled libraries or external mods.

## KltytonUI

Release files bundle KltytonUI 1.2.7, maintained by kltyton and based on
ApricityUI by Tower of Sighs and its contributors, under LGPL-2.1. The license
text is in `kui/LGPL-2.1.txt` beside this notice, and the complete corresponding
source and build materials for the bundled nine-target library are available at:

https://github.com/kltyton/KltytonUI/tree/1d64ed14830ae1809be4e14ddc97cba8f7caa76b

The Fabric 26.2 library's corresponding source and target build definitions are
available at:

https://github.com/kltyton/KltytonUI/tree/20bb8dc6a7b4fce076dcb038be10a3261ced6b6e

Upstream: https://github.com/Tower-of-Sighs/AUI

The modified library provides basic chunk mesh baking and GPU drawing, the
standalone Rhino bridge, and the Vue/McUI page runtime. Earth owns map cameras,
tile sessions and caches, scene composition, entity models and player indicators.
The map and entity code migrated from the modified AUI implementation is retained
with its LGPL-2.1 terms; the existing author and copyright notices remain in place.
Earth uses KltytonUI's chunk rendering and UI implementation as a separate nested library.

The source distribution documents how to build each KltytonUI target. Earth can
be rebuilt with an interface-compatible KltytonUI JAR by passing
`-PkuiJar=<path>` to the selected Earth target's Gradle wrapper.

## Rhino

Release files bundle the Minecraft Rhino fork by Mozilla, LatvianModder /
latvian.dev, and contributors, under MPL-2.0. Its license text is in
`rhino/MPL-2.0.txt`. Earth does not modify this library.

| Earth target | Bundled Rhino version | Source |
| --- | --- | --- |
| Forge 1.18.2 | 1802.2.1-build.255 | https://github.com/KubeJS-Mods/Rhino/tree/1802 |
| Forge 1.19.2 | 1902.2.3-build.284 | https://github.com/KubeJS-Mods/Rhino/tree/1902 |
| Forge / Fabric 1.20.1 | 2001.2.3-build.10 | https://github.com/KubeJS-Mods/Rhino/tree/2001 |
| Fabric / NeoForge 1.21.1 and 26.x | 2101.2.7-build.82 | https://github.com/KubeJS-Mods/Rhino |

## Other bundled components

- **MixinExtras 0.4.1**, LlamaLad7, MIT. Forge files retain
  `LICENSE_MixinExtras` inside the nested library.
  Source: https://github.com/LlamaLad7/MixinExtras/tree/0.4.1
- **JOML 1.10.5**, JOML contributors, MIT. KltytonUI's Forge 1.18.2 and 1.19.2 files
  include it as a nested library with `META-INF/LICENSE`.
  Source: https://github.com/JOML-CI/JOML/tree/1.10.5
- **Vue 3.5.34**, Yuxi (Evan) You and contributors, MIT. KltytonUI retains the runtime
  notice inside `assets/kltytonui/kltytonui/kltytonui/runtime/vue.kui.js`.
  Source: https://github.com/vuejs/core
- **McUI2**, Spectrollay and McUI contributors, MIT. KltytonUI retains the notice
  inside `assets/kltytonui/kltytonui/kltytonui/runtime/mcui/mcui-oreui.kui.js`.
  The existing OreUI compatibility theme retains its MPL-2.0 notices.

## External requirements

Xaero's World Map and XaeroLib (Xlib) are separate requirements and are not
redistributed in Earth release files. Xaero's Minimap is optional and is not
redistributed. Their code, artwork, names and licenses remain with their
respective owners. Minecraft and loader libraries are also supplied by the
user's game installation rather than bundled as Earth assets.
