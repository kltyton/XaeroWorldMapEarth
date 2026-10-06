# Xaero's World Map: Earth

**一句话描述：** 为 Xaero 世界地图加入原生三维地形、三种可切换视角与 Ore 风格界面。

## 简介

Xaero's World Map: Earth 是 Xaero 世界地图的客户端附属模组。它将你已探索的地图呈现为立体地形，让山坡、建筑和地表高差更容易观察，同时为地图按钮、设置页和滑块提供 Ore 风格界面。

你仍然使用 Xaero 的地图、路径点和右键菜单。可以在等轴、俯视和街景三种视角之间切换；关闭 Earth 主题后即可回到原地图界面。已有地图与路径点继续由 Xaero 管理。

## 具体功能

- **三种地图视角：** 等轴视角用于观察整体地形，俯视视角用于定位，街景视角可以在地图中移动相机、从侧面查看地形与建筑。
- **原生三维地形：** 可取得区块模型的区域使用 Minecraft 方块模型与当前资源包的材质。远处只有 Xaero 颜色和高度记录的区域显示粗略地形，模型可用后再补上细节。
- **Ore 风格界面：** 地图按钮、设置页、下拉菜单和滑块采用统一样式，保留原控件的功能与操作入口。
- **玩家与实体：** 玩家位置使用当前皮肤的头部模型，头部指示器保持明亮可见。安装匹配版本的 Xaero 小地图后，可将原雷达允许显示、且客户端实际加载的实体加入三维模型层；仍遵守 Xaero 的筛选与服务器权限。
- **沿用 Xaero 数据：** 已探索范围、世界、维度和洞穴层继续由 Xaero 决定。Earth 不修改世界生成，也不会将未知区域自动变成已探索地图。

## 安装

仅安装在客户端。请选择与你的 Minecraft 和加载器完全一致的 Earth 文件，并安装匹配版本的 **Xaero's World Map** 与 **XaeroLib（Xlib）**。Fabric 还需要 Fabric API。发行包已内置匹配的 ApricityUI（AUI）与 Rhino，无需另装 KubeJS。Xaero's Minimap 是可选项，用于实体雷达联动。

| Minecraft | 可用加载器 | Java |
| --- | --- | --- |
| 1.18.2 | Forge | 17 |
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2 | NeoForge、Fabric | 25 |
| 26.2 | NeoForge | 25 |

不同版本和加载器的文件不能混用。各文件声明了对应 Xaero 与 Xlib 的版本范围，请按所选文件的依赖安装。

## 基本操作

使用 Xaero 的地图按键打开地图。在等轴视角中按住鼠标中键旋转；街景中按住左键或中键调整视角，用 WASD 平移、Space 上升、Shift 下降。菜单、输入框和控件优先接收操作。

配置中的 `enabled` 控制 Earth 主题，`view` 可选择 `ISOMETRIC`、`TOP` 或 `STREET`，`modelIndicators` 控制实际实体模型。Forge／NeoForge 使用 `config/xaeroearth-client.toml`，Fabric 使用 `config/xaeroearth-client.properties`。直接修改 Fabric 配置文件后需重启客户端。

## 当前限制与反馈

精细地形取决于可取得的区块模型；只有颜色与高度记录的远处区域不包含完整建筑细节。首次打开地图仍可能有明显停顿，当前不承诺固定的帧率提升或消除首开卡顿。其他 Xaero 附属、特殊实体渲染和整合包组合需要分别验证。

遇到问题，请到 [GitHub Issues](https://github.com/kltyton/XaeroWorldMapEarth/issues) 提供 Minecraft、加载器、Earth、Xaero、Xlib 的版本、复现步骤和 `latest.log`；涉及显示问题时附上截图及所用资源包。

## 致谢与许可

本项目由 kltyton 开发，代码采用 MIT 许可。感谢 Xaero's World Map、[ApricityUI](https://github.com/Tower-of-Sighs/AUI) 与 mcui-oreui 的作者及贡献者。AUI 使用 LGPL-2.1，Rhino 使用 MPL-2.0，其余随包库保留各自许可；详见[第三方声明](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md)。
