# Xaero's World Map: Earth

**One-sentence summary:** 3D terrain, three camera views and an Ore-style interface for Xaero's World Map.

## See your map in three dimensions

Hills gain height, valleys gain depth, and buildings can be viewed from the side. Earth adds 3D terrain and a full set of Ore-style controls to Xaero's World Map.

Use the isometric view to survey the landscape, the top-down view to find locations and organize waypoints, or the street view to move the map camera through terrain and inspect buildings up close. Map buttons, settings, dropdowns and sliders share the Ore style, while your map key, waypoints and context-menu actions stay familiar.

## Terrain and markers

- **Minecraft models and resource-pack textures.** Areas with accessible chunk data use native block models and your current resource-pack textures. Distant areas use Xaero's recorded colors and heights, with model details added as they become available.
- **3D player heads.** Player positions use head models from their current skins, with bright indicators inside caves.
- **Minimap integration.** With Xaero's Minimap installed, loaded entities from its radar can join the map model layer, following its existing filters and server permissions.
- **Your existing map data.** Xaero manages exploration, worlds, dimensions, cave layers and waypoints. Disable the Earth theme in the configuration to switch back to the original map interface.

## Installation

Install on the client. Choose the Earth file for your Minecraft version and loader, then install **Xaero's World Map** and **XaeroLib (Xlib)**. Fabric also requires Fabric API.

Earth release files bundle matching versions of **ApricityUI (AUI) 1.2.7** and **Rhino**. **Xaero's Minimap** is an optional integration for entity models on the map.

| Minecraft | Loaders | Java |
| --- | --- | --- |
| 1.18.2 | Forge | 17 |
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2 | NeoForge, Fabric | 25 |
| 26.2 | NeoForge | 25 |

Follow the Xaero and Xlib version ranges listed for your selected release file.

## Controls

Open the map with your Xaero map key. Hold the middle mouse button to rotate the isometric view. In street view, hold the left or middle mouse button to look around, use WASD to move, Space to rise and Shift to descend.

The `enabled` setting controls the Earth theme, `view` selects `ISOMETRIC`, `TOP` or `STREET`, and `modelIndicators` controls entity models. Forge and NeoForge use `config/xaeroearth-client.toml`; Fabric uses `config/xaeroearth-client.properties`. Restart the Fabric client after editing its configuration file.

## Feedback and credits

Share version details, reproduction steps, logs and screenshots through [GitHub Issues](https://github.com/kltyton/XaeroWorldMapEarth/issues).

Developed by kltyton, with project code under the MIT license. Thanks to the authors and contributors of Xaero's World Map, [ApricityUI](https://github.com/Tower-of-Sighs/AUI) and mcui-oreui. AUI uses LGPL-2.1 and Rhino uses MPL-2.0. The [third-party notices](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md) list the licenses and sources of bundled libraries.
