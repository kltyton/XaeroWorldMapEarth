# Xaero's World Map: Earth

Explore your Minecraft world as a 3D landscape inside Xaero's World Map. Inspired by Google Earth, this client-side addon adds isometric, top-down, and street views, an optional 3D HUD minimap, and navigation to destinations you pick on the map.

## Explore the map

Nearby terrain and buildings use Minecraft block models and your current resource-pack textures. Farther out, the map uses the heights and colors Xaero has already recorded. Switch to street view to move the camera through the landscape, or adjust the isometric angle to get a better view of the terrain.

Earth gives the map and settings an Ore-McUI interface. Player markers use the heads from their current skins and stay visible in caves. Xaero's waypoints, dimensions, cave layers, and existing map data are available as usual. You can switch back to Xaero's original interface in the settings.

## Find your way

Right-click a destination on the world map and choose **Navigate here** to show a 3D route, a highlighted destination, and the remaining distance. Choose **Auto-navigate here** to let Earth steer and move you along the route. It stops when you arrive, and you can cancel with `Esc`.

Auto-navigation can also steer a mount you are controlling. If you are already gliding with an elytra, it can use firework rockets held in your main hand or off hand when flight conditions allow.

## Optional integrations

- **Xaero's Minimap** adds Earth terrain to the HUD, with the world map's selected view and Xaero's minimap zoom and orientation settings. The world map can also display entity models allowed by Xaero's radar filters and server permissions.
- **CompanionCall** lets you send movement orders from the world map when installed with a compatible build. Click a companion, pet, or mount portrait to select it. Use `Shift`-click to add or remove a selection, or `Shift`-drag to select a group. Then click a location on the map to send the selected companions there.

## Controls

| Action | Input |
| --- | --- |
| Open the world map | Xaero's map key, `M` by default |
| Pan in isometric or top-down view | Left-drag |
| Rotate in isometric view | Middle-drag |
| Reset the isometric angle | Double-click the Isometric button |
| Zoom | Mouse wheel |
| Change view | Use the buttons at the bottom of the map |
| Look around in street view | Left- or middle-drag |
| Move in street view | `WASD` to move, `Space` to rise, `Shift` to descend |
| Manage waypoints | Use Xaero's existing waypoint controls |

## Installation

Install Earth on the client and choose the file that matches your Minecraft version and loader. It requires **Xaero's World Map** and **XaeroLib (Xlib)**. Fabric players also need **Fabric API**. The 0.1.3 files bundle KltytonUI 1.3.1 and its NeonJS runtime.

| Minecraft | Loaders | Java |
| --- | --- | --- |
| 1.18.2 and 1.19.2 | Forge | 17 |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2 and 26.2 | NeoForge, Fabric | 25 |

## Similar projects

Testers introduced me to [Map 3d usseewa](https://www.curseforge.com/minecraft/mc-mods/map-3d-usseewa) and [x3d map](https://www.curseforge.com/minecraft/mc-mods/x3d-map) after development had already started. Earth was developed independently.

Developed by kltyton. Earth is licensed under MIT. KltytonUI, based on ApricityUI, is licensed under LGPL-2.1.

[Source](https://github.com/kltyton/XaeroWorldMapEarth) · [Releases](https://github.com/kltyton/XaeroWorldMapEarth/releases) · [Issues](https://github.com/kltyton/XaeroWorldMapEarth/issues) · [Third-party notices](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md)
