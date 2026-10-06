# Xaero 的世界地图：地球

**一句话简介：** 为 Xaero 世界地图加入 3D 地形、三种视角和 Ore 主题界面。

**Xaero's World Map: Earth** 是 Xaero 世界地图的客户端附属模组。它使用 Minecraft 方块模型绘制三维地形，支持地图旋转、缩放和视角切换，并为地图与设置界面提供 Ore-McUI 风格的控件。

## 功能列表

| 功能 | 说明 |
| --- | --- |
| 3D 地形地图 | 使用方块模型和当前资源包材质展示地形与建筑；远处区域使用 Xaero 记录的颜色和高度，模型加载后更新细节 |
| 三种地图视角 | 在等轴、俯视、街景之间切换；街景支持自由移动地图相机 |
| Ore 风格界面 | 地图按钮、设置页、下拉菜单与滑块使用 Ore-McUI 样式 |
| 路径点管理 | 继续使用 Xaero 的路径点创建、编辑、删除和右键菜单 |
| 立体玩家头像 | 使用玩家当前皮肤的头部模型标记位置，洞穴中保持明亮 |
| 小地图实体联动 | 共装 Xaero's Minimap 后，将原雷达允许显示的已加载实体加入三维模型层 |
| 主题切换 | 通过配置启用或关闭 Earth 主题，切换三维界面与 Xaero 原界面 |
| 世界与洞穴图层 | 沿用 Xaero 世界地图的世界、维度和洞穴层选择，以及已有地图数据 |

## 操作说明

| 操作 | 按键 |
| --- | --- |
| 打开地图 | Xaero 世界地图按键，默认 `M`，可修改 |
| 平移地图 | 等轴或俯视视角下，在地图区域按住左键拖动 |
| 旋转地图 | 等轴视角下按住中键拖动 |
| 缩放地图 | 鼠标滚轮 |
| 切换视角 | 地图中的等轴、俯视、街景按钮 |
| 转动街景相机 | 按住左键或中键拖动 |
| 移动街景相机 | `WASD` 平移，`Space` 上升，`Shift` 下降 |
| 管理路径点 | 使用 Xaero 原有右键菜单和路径点界面 |

## 安装

安装在客户端，并选择与 Minecraft 版本和加载器对应的文件。

**必需模组：**

- Xaero's World Map
- XaeroLib（Xlib）
- Fabric API（Fabric 版本）

**可选联动：** Xaero's Minimap，用于地图中的实体模型显示。实体筛选与服务器权限沿用原小地图设置。

发行包已内置匹配的 ApricityUI（AUI）1.2.7 与 Rhino。

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.18.2 | Forge | 17 |
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2 | NeoForge、Fabric | 25 |
| 26.2 | NeoForge | 25 |

## 作者与链接

由 kltyton 开发。项目代码采用 MIT 许可；AUI 采用 LGPL-2.1，Rhino 采用 MPL-2.0。

[源码](https://github.com/kltyton/XaeroWorldMapEarth) · [问题反馈](https://github.com/kltyton/XaeroWorldMapEarth/issues) · [第三方声明](https://github.com/kltyton/XaeroWorldMapEarth/blob/main/licenses/NOTICE.md)
