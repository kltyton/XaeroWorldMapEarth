# Xaero 的世界地图：地球

**一句话描述：** 把 Xaero 世界地图变成立体地形，配上等轴、俯视、街景三种视角和 Ore 风格界面。

## 从平面地图到立体世界

山坡有了高度，河谷有了纵深，建筑也能从侧面看。Earth 为 Xaero 世界地图加入三维地形和全套 Ore 风格控件，让同一张地图有三种看法。

等轴视角适合看地形全貌；俯视视角方便找位置、整理路径点；街景视角让你移动地图相机，近距离观察地形和建筑。地图按钮、设置页、下拉菜单与滑块都换上统一的 Ore 风格，原有地图按键、路径点和右键操作继续使用。

## 地形与标记

- **原生方块模型与材质。** 可读取区块的区域展示 Minecraft 方块模型和当前资源包材质；远处则用 Xaero 记录的颜色与高度铺成立体地形，区块模型就绪后补上细节。
- **立体玩家头像。** 玩家位置使用当前皮肤的头部模型，洞穴里也保持明亮。
- **小地图实体联动。** 共装 Xaero's Minimap 后，雷达中已加载的实体可以加入地图模型层，筛选条件和服务器权限沿用 Xaero 设置。
- **沿用现有地图。** 探索范围、世界、维度、洞穴层和路径点由 Xaero 管理。通过配置关闭 Earth 主题，即可切回原地图界面。

## 安装

安装在客户端，选择对应 Minecraft 版本和加载器的 Earth 文件，再安装 **Xaero's World Map** 与 **XaeroLib（Xlib）**。Fabric 同时安装 Fabric API。

Earth 发行包内置匹配的 **ApricityUI（AUI）1.2.7** 与 **Rhino**。**Xaero's Minimap** 是可选联动模组，用于地图中的实体模型。

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.18.2 | Forge | 17 |
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2 | NeoForge、Fabric | 25 |
| 26.2 | NeoForge | 25 |

Xaero 与 Xlib 的版本范围随对应发行文件声明，按文件依赖安装即可。

## 操作

使用 Xaero 的地图按键开图。等轴视角按住鼠标中键旋转；街景视角按住左键或中键转动相机，WASD 平移，Space 上升，Shift 下降。

配置项 `enabled` 控制 Earth 主题，`view` 选择 `ISOMETRIC`、`TOP` 或 `STREET`，`modelIndicators` 控制实体模型。Forge／NeoForge 配置位于 `config/xaeroearth-client.toml`，Fabric 位于 `config/xaeroearth-client.properties`。修改 Fabric 配置文件后重启客户端生效。

## 反馈与致谢

在 [GitHub Issues](https://github.com/kltyton/XaeroWorldMapEarth/issues) 提交版本信息、复现步骤、日志和截图。

由 kltyton 开发，项目代码采用 MIT 许可。感谢 Xaero's World Map、[ApricityUI](https://github.com/Tower-of-Sighs/AUI) 与 mcui-oreui 的作者及贡献者。AUI 使用 LGPL-2.1，Rhino 使用 MPL-2.0；[第三方声明](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md)列明随包库的许可与源码。
