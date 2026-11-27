# Third-party notices

Xaero's World Map: Earth is developed by kltyton and licensed under MIT. Its
license does not replace the licenses of bundled libraries or external mods.

## KltytonUI

The current build bundles KltytonUI 1.3.2.1, maintained by kltyton and based on
ApricityUI by Tower of Sighs and its contributors, under LGPL-2.1. The license
text is in `kui/LGPL-2.1.txt` beside this notice. The matching library source
and build materials are available at:

https://github.com/kltyton/KltytonUI

Upstream: https://github.com/Tower-of-Sighs/AUI

The modified library provides basic chunk mesh baking and GPU drawing, the
script bridge, and the NeonJS-backed Vue/McUI page runtime. Earth owns map cameras,
tile sessions and caches, scene composition, entity models and player indicators.
The map and entity code migrated from the modified AUI implementation is retained
with its LGPL-2.1 terms; the existing author and copyright notices remain in place.
Earth uses KltytonUI's chunk rendering and UI implementation as a separate nested library.

The source distribution documents how to build each KltytonUI target. Earth can
be rebuilt with an interface-compatible KltytonUI JAR by passing
`-PkuiJar=<path>` to the selected Earth target's Gradle wrapper.

## Other bundled components

- **NeonJS 0.1.4**, bundled by KltytonUI. The runtime archive carries its
  license, notices and third-party attributions.
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
