# VGM 浏览器 (VgmBrowser)

J2ME VGM 播放器，实时将 AY8910 寄存器流转成 MIDI 事件输出到手机音源。

## 功能

- VGM 文件浏览与自动播放（支持循环）
- AY8910 → MIDI 7 通道实时转换：
  - MIDI 0/1/2：方波 A/B/C（默认音色 80 Square Lead）
  - MIDI 3/4/5：噪声 A/B/C（默认音色 127，独立声道各带声像）
  - MIDI 6：包络波形/蜂鸣器（默认音色 83 Sawtooth）
- 可调参数（左右键实时切换）：
  - 声像模式：ABC / ACB / BAC / BCA / CAB / CBA / MONO
  - 八度移位：-3..+3（每档 12 半音）
  - 7 通道独立音色映射（16 个常用 GM 音色池）
- 播放控制：
  - 4/6 键：上一曲/下一曲
  - 5 键：暂停/继续
  - 0 键：停止
  - ↑↓ 键：移动设置行光标
  - ←→ 键：调整光标所在项

## 技术细节

### 包络三分支（核心转换逻辑）

| 分支 | 判定 | 处理 |
|---|---|---|
| 波形（快包络） | env 模式 && 包络频率 ≥ 20Hz && 重复形状 | 蜂鸣器通道（音色83），音高 = clock/(16×period×steps) |
| 同步蜂鸣器 | 一次性形状被 ≥20Hz 高速重写 | 音高 = 1000/重写间隔ms，每次写入 re-articulation，100ms 超时关音 |
| 音量模拟（慢包络） | 其余 env 模式 | 软件包络仿真（emu2149 状态机移植）→ CC11 起伏 |

- 锯齿类形状（R13∈8,9,11,12,13,15）32 步；三角类（10,14）64 步——
  八度差自然包含在频率公式分母里
- 一次性形状（0-7）由 continue/attack/alternate/hold 四位驱动，
  仿真到 hold/终止后电平停驻
- R13 写入 = 包络发生器重启（硬件语义）

### 音量与力度

- AY 音量语义：**数值越大越响**（0=静音，15=最响），与 SN76489 相反
- CC7 (Main Volume) 固定 127；CC11 (Expression) 承载全部动态（含慢包络仿真电平）
- 音量表 = yasp11 VOL_TO_TL 取反（非线性）：{0,65,71,75,81,85,91,95,99,103,107,111,115,119,123,127}
- Note On velocity 固定 100（PSG 靠通道音量而非力度触发，WonderSwan 转换器同款范式）

### VGZ 支持

MIDP/CLDC 无 java.util.zip，DEFLATE 从零实现（puff.c 式）：
stored/fixed/dynamic 块、规范哈夫曼解码、LZ77 回拷；gzip 头可选字段全解析。
三个样本字节级验证一致，命令数与裸 VGM 完全吻合。

### 弯音

- RPN 0x00/0x00 设置 ±12 半音（覆盖 VGM 大范围滑音）
- 14 位弯音轮，定点 log2 查表（256 项，~7 音分精度）

### 转换语义

以用户源码为准（yasp11 console_player959 ay_to_opm.c + Denjhang v16）：

- **频率公式**：`clock / 16 / 分频值`（三方参考一致：libvgm emu2149、vgm2mid、v16）
- **蜂鸣器判据**：`(vol寄存器 bit4 置位) && (包络周期 < 200)`（yasp11 _updateTone）
  - 此时即使 mixer 的 tone 位关闭也照响
- **音量映射**：yasp11 的非线性响度表（`ATTN_TO_VOL` / `N_ATT_TO_VOL`）
- **弯音范围**：RPN 设置为 ±12 半音（覆盖 VGM 大范围滑音）

### 音高换算

- 定点 log2 查表（256 项，精度约 7 音分），无浮点依赖（CLDC 1.1 没有 Math.log）
- 公式：`n = 69 + 12*log2(f/440)`
- Python 预生成正确表值硬编码

### 交叉验证参考

1. **libvgm/emu2149.c**：`clk/(freq*16)`（芯片仿真权威实现）
2. **vgm2mid AY8910.bas**：`(ClockAY/16)/FNum`（Valley Bell 2013）
3. **yasp11 console_player959 ay_to_opm.c**：`clock/(16*period)`（用户最新版）

## 编译

```bash
./build.sh tools/vgm-browser
```

产物：`VgmBrowser.jar` / `VgmBrowser.jad`

## 运行

- **KEmulator**：`./run-kemulator.sh tools/vgm-browser/VgmBrowser.jar`
- **真机**：拷贝 jar+jad 到 E5，从"游戏和应用"安装

## 依赖

- CLDC 1.1 / MIDP 2.0
- JSR 135 (MMAPI) - MIDI Control
- JSR 75 (FileConnection) - 文件浏览

## 许可

参考 libvgm (GPLv2/LGPLv2.1)、vgm2mid 原始许可。
