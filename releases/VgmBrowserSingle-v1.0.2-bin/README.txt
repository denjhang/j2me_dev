v1.0.2: fix NPE when painting before decompression finishes (file==null guard + loading screen); 30fps repaint + debug row cache from v1.0.1

-- 现阶段最终状态 --
模拟器结论：单缓冲明显比双缓冲流畅，为当前最理想状态。
S40 模拟器剩余的轻微卡顿是 SMF 分块播放的固有代价，不可避免；
只有实时流输出（真机实时合成/PCM）才能彻底消除，现阶段接受。
