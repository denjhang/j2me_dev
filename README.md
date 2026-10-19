# j2me_dev — J2ME 开发工作区

从 [Maemo_N900](../Maemo_N900) 仓库剪切出来的独立工作区（2026-10-19）。

**先读 [HANDOFF.md](./HANDOFF.md)** —— 交接文档（资料清单 + 技术选型 + 前期调研结论 + 下一步）。
**技术调研原文见 [RESEARCH.md](./RESEARCH.md)** —— 音频路线的源码级结论（原 N900 PROGRESS.md 第廿二章）。

## 快速开始

```bash
# 资料都在本地（不入 git，约 878MB）
tools/wtk252_win.exe                    # Sun 官方 WTK 2.5.2
emulators/KEmulator-nnmod/              # ★首选模拟器
references/awesome-j2me/readme.md       # J2ME 全景清单
references/psg-core/                    # N900 的 AY8910 C 源码副本（移植 Java 用）
```

## 环境提醒

- 本机 Java 26，**KEmulator nnmod 要求 Java 8** —— 开工第一件事是解决这个
- 开发循环可以在 PC 完全闭环（编译→模拟器→截图验证），不用开真机
- 部署到 E5：`.jar` 拷进去即可，**无需签名**

## 目录

| 路径 | 内容 | 入库 |
|---|---|---|
| `HANDOFF.md` / `README.md` / `RESEARCH.md` | 文档 | ✅ |
| `tools/` | WTK 2.5.2 | ❌ |
| `emulators/` | KEmulator-nnmod / FreeJ2ME / FreeJ2ME-Plus / SquirrelJME | ❌ |
| `references/` | fplay / midplay / j2meloader / awesome-j2me / huayuechenfeng / psg-core | ❌ |
| `assets/` | nnproject 两份抓取（595MB） | ❌ |
