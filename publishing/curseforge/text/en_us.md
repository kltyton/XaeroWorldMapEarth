# Xaero's World Map: Earth

**One-sentence summary:** Adds native 3D terrain views and an Ore-style interface to Xaero's World Map.

## Description

Xaero's World Map: Earth is a client-side addon for Xaero's World Map. It presents explored areas as three-dimensional terrain, making hills, buildings and changes in elevation easier to see, and gives map buttons, settings and sliders an Ore-style interface.

You keep using Xaero's map, waypoints and context menus. Switch between isometric, top-down and street views, or disable the Earth theme to return to the original interface. Xaero continues to manage your existing maps and waypoints.

## What it does

- **Three camera views:** Use the isometric view to inspect terrain, the top-down view to find locations, or the street view to move through the map and look at terrain and buildings from the side.
- **Native 3D terrain:** Areas with available chunk models use Minecraft block models and textures from your current resource packs. Distant areas with only Xaero color and height records use coarse terrain until detailed models become available.
- **Ore-style controls:** Map buttons, settings, dropdowns and sliders share a consistent style while keeping their original functions and entry points.
- **Players and entities:** Player positions use head models from their current skins, with bright head indicators. With a matching version of Xaero's Minimap installed, locally loaded entities permitted by its radar can appear as 3D models. Xaero's filters and server permissions still apply.
- **Existing Xaero data:** Xaero determines explored areas, worlds, dimensions and cave layers. Earth does not change world generation or automatically reveal unexplored areas.

## Installation

Install on the client only. Choose the Earth file for your exact Minecraft version and loader, then install matching versions of **Xaero's World Map** and **XaeroLib (Xlib)**. Fabric also requires Fabric API. Release files bundle matching versions of ApricityUI (AUI) and Rhino; KubeJS is not required. Xaero's Minimap is optional and enables entity radar integration.

| Minecraft | Available loaders | Java |
| --- | --- | --- |
| 1.18.2 | Forge | 17 |
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2 | NeoForge, Fabric | 25 |
| 26.2 | NeoForge | 25 |

Files for different versions or loaders are not interchangeable. Follow the Xaero and Xlib version ranges declared by your selected file.

## Controls

Open the map with your Xaero map key. Hold the middle mouse button to rotate the isometric view. In street view, hold the left or middle mouse button to look around, use WASD to move, Space to rise and Shift to descend. Menus, text fields and controls take input priority.

The `enabled` setting controls the Earth theme, `view` selects `ISOMETRIC`, `TOP` or `STREET`, and `modelIndicators` controls actual entity models. Forge and NeoForge use `config/xaeroearth-client.toml`; Fabric uses `config/xaeroearth-client.properties`. Restart the Fabric client after editing its configuration file directly.

## Current limits and feedback

Detailed terrain depends on available chunk models. Distant areas represented only by color and height records do not include complete building details. Opening the map for the first time can still cause a noticeable pause; this release does not promise a fixed FPS gain or eliminate first-open stalls. Other Xaero addons, unusual entity renderers and modpack combinations require separate testing.

Report problems through [GitHub Issues](https://github.com/kltyton/XaeroWorldMapEarth/issues) with Minecraft, loader, Earth, Xaero and Xlib versions, reproduction steps and `latest.log`. For visual problems, include a screenshot and the resource packs in use.

## Credits and licenses

Developed by kltyton, with project code under the MIT license. Thanks to the authors and contributors of Xaero's World Map, [ApricityUI](https://github.com/Tower-of-Sighs/AUI) and mcui-oreui. AUI is licensed under LGPL-2.1, Rhino under MPL-2.0, and other bundled libraries retain their own licenses. See the [third-party notices](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md).
