VgmBrowserSingle v1.0.36
========================

修复双芯片/芯片类型识别错误（369 个测试文件中 293 个误判 -> 0 个误判）。

## Bug 修复

### 1. 双芯片判定错误
- **错误**：用 bit31 (0x80000000) 判断，导致单芯片 YM2149 文件被误判为双芯片
- **修复**：改用 bit30 (0x40000000)，符合 libvgm 规范

### 2. 芯片类型偏移错误  
- **错误**：从 0x7C/0x7D 读取 AY chipType/flags
- **修复**：改为 0x78/0x79（libvgm AY8910_CFG 标准位置）
  - 0x78: chipType (0x00=AY8910, 0x10=YM2149, 0x11=YM3439 等)
  - 0x79: chipFlags (YM2149 pin26 状态、ZX Spectrum 立体声等)

### 3. 第二片时钟读取错误
- **问题**：0x78 不是第二片时钟（那是单字节 chipType），真实来源是扩展头 0xBC
- **当前**：暂不支持扩展头，双芯片时钟回退为 clk1（libvgm 同样回退）

## 验证结果

测试集：369 个 VGM/VGZ 文件（TurboSound、单芯片、修剪版等）

| 指标 | v1.0.35 | v1.0.36 |
|---|---|---|
| 双芯片误判 | 293 | 0 |
| 芯片类型误判 | 293 | 0 |
| 地面真值一致 | 76 | 369 |

剩余 37 个 ref/truth 不一致为**文件制作错误**（头部 bit30=1 但命令流无 0xA1），
非程序 bug。

## 技术细节

参考 libvgm (modizer) 实现：
- `VGMPlayer::GetChipCount()`: `(clock & 0x40000000) ? 2 : 1`
- `VGMPlayer::_CHIPCLK_OFS[0x12]` = 0x74 (AY8910 时钟位置)
- `vgmplayer.cpp:1251`: `ayCfg.chipType = _hdrBuffer[0x78]`

---
构建日期：2026-10-24
