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
