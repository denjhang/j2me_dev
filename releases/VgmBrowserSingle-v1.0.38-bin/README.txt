VgmBrowserSingle v1.0.38 - 修复 PC 端音量力度映射
=====================================================

## 问题
v1.0.32 引入双音量设置后，PC 端回放效果变差，无法通过选项恢复 v1.0.31 效果。

## 根因
retriggerVel() 强制使用 vel(level) 作为力度，忽略 volMode 设置。

## 修复
- retriggerVel() 现在遵循 volMode 设置
- volMode=0/1 时力度恒 127（恢复 v1.0.31 行为）
- volMode=2 时使用 vel(level)（三重模式）

## 使用
PC 端：设置 "Note-on vol" = API，即可恢复 v1.0.31 效果
真机：默认设置（CC7 + retrig）最佳兼容性
