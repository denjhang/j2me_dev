---
name: j2me-sign
description: J2ME (MIDP) jar 签名与 JAD 生成。凡是要把 jar/jad 装到诺基亚 S40/S60 等真机、提示"jar 和 jad 属性不匹配 / URL is not valid / open connection failed"、需要自签名证书、keytool/jarsigner 报 SHA1 被禁用 (disabledAlgorithms)、或要批量给第三方 jar 配套 JAD 时必用。默认走未签名+干净 JAD 路线（真机可直接装），需要真验签时才加签名。
---

# J2ME jar 签名与 JAD 生成

## 核心判断（先读这段再动手）

真机安装失败时 90% 不是"缺证书"，而是 JAD 本身有问题。判断顺序：

1. **JAD 四要素**：CRLF 行尾、无 BOM、纯 ASCII、`MIDlet-Jar-Size` 与 jar 实际字节数一致。任一不满足，诺基亚报"属性不匹配"或直接拒装。
2. **`MIDlet-Jar-RSA-SHA1` 是验签开关**：JAD 里写了这个属性，手机就强制验签；自签名证书不在手机信任库时，验签失败被诺基亚固件报成"jar 和 jad 属性不匹配"（文案误导）。**不需要验签就绝不要写这个属性**——未签名 jar + 干净 JAD 在 S40/S60 上能直接安装，只有权限提示。
3. 文件名只用安全 ASCII：`City Bloxx (2007)(Nokia).jar` 这种带空格/括号的名字会让部分安装器解析 `MIDlet-Jar-URL` 失败。
4. JAD 属性值必须与 jar 内 MANIFEST.MF 逐字一致——不要手写模板，从 manifest 提取。

## 用脚本（不要手动敲 keytool/jarsigner）

```bash
# 未签名 + 干净 JAD（绝大多数场景，输出 <目录>/<名>.jar + .jad，自动自检）
python .agents/skills/j2me-sign/scripts/sign_jad.py <input.jar> <输出目录> --name SafeName

# 真签名（自动生成证书、SHA1withRSA、写 MIDlet-Jar-RSA-SHA1）
python .agents/skills/j2me-sign/scripts/sign_jad.py <input.jar> <输出目录> --name SafeName \
    --signed --keystore <ks路径> --storepass <口令> \
    --jdk "$(cygpath -w tools/jdk8/bin)"   # 本仓库 JDK8；其他环境指向任意 JDK8 bin
```

脚本内置：
- jarsigner 的 SHA1 禁用覆盖 `-J-Djdk.jar.disabledAlgorithms=`（JDK 8u302+ 默认禁 SHA1，必须覆盖，只影响该进程）
- 非标准 zip（jarsigner 报 `ZipException: invalid distance too far back`）自动逐条目重压缩再签，损坏条目按原始字节保留
- 未签名模式自动剥离 jar 里残留的 `.SF/.RSA/.DSA`
- JAD 二进制模式写 CRLF（文本模式会把 `\r\n` 吃成 `\n`——真机 LF 的 JAD 解析异常）
- 输出前自检：BOM / 行尾 / 大小 / 主类存在 / 签名文件存在

## 签名后仍要做的

自签名证书要让手机信任：
1. 导出：`keytool -exportcert -keystore <ks> -alias <alias> -storepass <口令> -file cert.cer -rfc`
2. `cert.cer` 拷到手机打开导入（S60 问信任域，选"应用软件"/默认）
3. 之后该证书签的所有 jar 按已识别发布者处理

本仓库已有现成证书：`tools/vgm-browser/vgm.keystore`（alias `vgm`，口令 `vgmbrowser`，RSA 2048，10 年），导出件 `tools/vgm-browser/vgm-cert.cer`。

## 真机测试清单

- jar + jad 一起拷，**从 jad 启动安装**
- 首次读文件会弹一次权限询问（`file.read` 必选权限的正常行为）
- 若报属性不匹配：先查 JAD 四要素和 RSA-SHA1 开关，最后才怀疑证书
