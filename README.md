# Speculonic

[简体中文](#简体中文)

---

Speculonic is an open-source OpenSubsonic / Subsonic (v1.16.1) music client built with native Android technologies. Compatibility with Navidrome servers has been fully verified.

The project is built on the philosophy of treating the application as a local mirror of the remote Subsonic server. It provides synchronization and diff capabilities with remote Subsonic servers, alongside the performance and extensibility of a high-performance local music player.

## Features

* **Native Android Development**: Built on native Android Jetpack Compose and Kotlin technologies. Supports adaptive responsive layouts for both mobile phones and tablets/large screens. Uses ExoPlayer as the playback engine.
* **Subsonic Local Mirror**: Provides incremental Subsonic metadata synchronization, persisted to a local database. Built-in consistency validation analyzes and reconciles discrepancies between cached music files and remote Subsonic server data.
* **Car Connectivity**: Detects car bluetooth profiles and device fingerprints, hijacking the underlying AVRCP protocol to push real-time scrolling lyrics to car dashboards.
* **Theme System**: Supports dark theme. The playback UI performs dynamic color extraction and contrast checks from the current album art, providing Gaussian-blurred and glow-gradient dynamic backgrounds.

## Roadmap

- [ ] Provide Github Action builds
- [ ] Publish on F-Droid
- [ ] Fix low-priority code defects
- [ ] Refactor tablet UI layout
- [ ] Provide Android X86 builds
- [x] Deliver compatibility updates within 3 months of new Android API releases

## License & Distribution Guidelines

The source code of this software is licensed under the GNU Affero General Public License v3.0 (AGPLv3). For detailed copyright information, please refer to the COPYRIGHT file in the root directory.

If you distribute a modified version of this software:
* You must comply with the AGPLv3 license requirements;
* You must clearly indicate significant modifications made to the codebase;
* You may not use the Speculonic name, logo, icon, or official branding assets without explicit, prior written permission from the copyright holder.

---

## 简体中文

Speculonic 是一款使用 Android 原生技术开发的开源 OpenSubsonic / Subsonic (v1.16.1) 音乐客户端, 已验证与 Navidrome 服务器的兼容性.

本项目基于 "将 App 作为远程 Subsonic 服务器的本地镜像" 的设计理念, 具备与远程 Subsonic 服务器同步与对比差异的能力, 同时具备本地播放器的高性能与扩展接口.

## 功能特性

* **Android原生开发**: 基于 Android 原生Jetpack Compose和kotlin技术构建. 支持手机端与平板/大屏的响应式自适应布局. 播放引擎为 ExoPlayer.
* **Subsonic本地镜像**: 提供增量Subsonic元数据同步机制, 并持久化到本地数据库. 内置数据一致性校验, 能分析并修复已缓存音乐文件与远程Subsonic服务器中数据的差异.
* **车辆互联适配**: 高精度嗅探车机蓝牙广播与设备指纹, 劫持底层 AVRCP 协议, 将歌曲实时同步歌词投送到车载屏幕.
* **主题系统**: 支持深色主题. 播放界面可根据当前曲目封面执行动态色彩提取与对比度校验, 提供高斯模糊和微光渐变两种美观的播放器背景.

## 开发路线图

- [ ] 提供 Github Action 构建
- [ ] 上架 F-Droid
- [ ] 修复一些低优先级代码缺陷
- [ ] 重构平板电脑的 UI
- [ ] 提供 Android X86 版本
- [x] 在 Android 新 API 版本发布 3 个月内提供适配

## 开源协议与分发许可

本软件的源代码采用 GNU Affero General Public License v3.0 (AGPLv3) 协议开源. 详细版权条款参见根目录 COPYRIGHT 文件.

如果您分发本软件的修改版本:
* 您必须遵守 AGPLv3 许可协议的要求;
* 您必须清晰地标明对原有代码的重大修改;
* 未经版权所有者明确许可, 您不得在分发版本中使用 Speculonic 的名称, Logo, 图标或官方品牌资产.
