# Third-party notices

Xaero's World Map: Earth is developed by kltyton and licensed under MIT. Its
license does not replace the licenses of bundled libraries or external mods.

## ApricityUI (AUI)

Release files bundle a modified ApricityUI 1.2.5.4 library. ApricityUI is by
Tower of Sighs and its contributors and is licensed under LGPL-2.1. The license
text is in `aui/LGPL-2.1.txt` beside this notice, and the complete corresponding
source and build materials for the bundled nine-target library are available at:

https://github.com/kltyton/AUI/tree/dev/earth-source-1.2.5.4

Upstream: https://github.com/Tower-of-Sighs/AUI

The modified library provides native map terrain, entity rendering and the
standalone Rhino bridge used by Earth. Existing author and copyright notices
are retained in the source. Earth uses AUI as a separate nested library and does
not copy its implementation into the Earth source tree.

The source distribution documents how to build each AUI target. Earth can be
rebuilt with a modified, interface-compatible AUI JAR by passing
`-PauiJar=<path>` to the selected Earth target's Gradle wrapper.

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
- **JOML 1.10.5**, JOML contributors, MIT. AUI's Forge 1.18.2 and 1.19.2 files
  include it as a nested library with `META-INF/LICENSE`.
  Source: https://github.com/JOML-CI/JOML/tree/1.10.5
- **Vue 3**, Yuxi (Evan) You and contributors, MIT. AUI retains the runtime
  license at `assets/apricityui/apricity/apricityui/runtime/vue-license.txt`.
  Source: https://github.com/vuejs/core
- **mcui-oreui**, Spectrollay's original OreUI and mcui-oreui contributors,
  MIT. AUI retains their notices at
  `assets/apricityui/apricity/apricityui/runtime/mcui/LICENSE.txt` and in its
  theme directories. The AUI theme adapters retain their MPL-2.0 notices.

## External requirements

Xaero's World Map and XaeroLib (Xlib) are separate requirements and are not
redistributed in Earth release files. Xaero's Minimap is optional and is not
redistributed. Their code, artwork, names and licenses remain with their
respective owners. Minecraft and loader libraries are also supplied by the
user's game installation rather than bundled as Earth assets.
