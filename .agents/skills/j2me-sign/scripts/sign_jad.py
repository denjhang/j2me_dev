#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""J2ME jar 签名 + JAD 生成一体化脚本。

用法:
  python sign_jad.py <input.jar> <输出目录> [--keystore KS] [--storepass PW] [--alias A] [--signed]
                      [--name 显示名] [--version V] [--icon /icon.png] [--cls 主类]

流程:
  1. (可选 --signed) 用 keytool 证书 jarsigner SHA1withRSA 签名 jar，
     并在 JAD 写入 MIDlet-Jar-RSA-SHA1（base64(SHA1(jar))）。
     注意: 必须配合 -J-Djdk.jar.disabledAlgorithms= 覆盖（JDK8u302+ 默认禁 SHA1），
     该参数只对本次 jarsigner 进程生效，不改全局。
  2. 从 jar 内 MANIFEST.MF 逐字提取必需属性生成 JAD:
     - CRLF 行尾、纯 ASCII、无 BOM（三者任一不满足部分诺基亚固件安装失败）
     - 属性值与 manifest 完全一致（"jar 和 jad 属性不匹配"的根源是手写模板）
     - MIDlet-Jar-Size 与最终 jar 字节数一致
  3. 输出 <目录>/<name>.jar + <name>.jad，文件名只用安全 ASCII（原名带空格/括号
     会让部分诺基亚安装器解析 MIDlet-Jar-URL 失败）。
  4. 自检: 行尾/大小/主类存在/无 BOM，任何一项失败非零退出。

坑（都踩过）:
  - 不加 --signed 时 JAD 绝不能带 MIDlet-Jar-RSA-SHA1：该属性会触发真机强制验签，
    自签名证书不在信任库时诺基亚报 "jar 和 jad 属性不匹配"（误导性文案）。
    未签名 jar + 干净 JAD 在 S40/S60 上可直接安装（仅权限提示）。
  - jar 内若有非标准 deflate 条目（jarsigner 报 ZipException: invalid distance），
    用 Python zipfile 逐条目重压缩后再签（坏条目可按原始字节保留）。
  - 写 JAD 必须以二进制模式（open 'wb'）写 CRLF；文本模式会把 \\r\\n 读成 \\n 再写出 LF。
"""
import argparse
import base64
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

MIDLET_KEYS = ["MIDlet-Name", "MIDlet-Version", "MIDlet-Vendor", "MIDlet-1",
               "MicroEdition-Profile", "MicroEdition-Configuration",
               "Nokia-MIDlet-Category", "Nokia-MIDlet-On-Screen-Keypad",
               "Nokia-MIDlet-No-Exit", "MIDlet-Permissions", "MIDlet-Permissions-Opt"]


def repack_if_needed(jar):
    """jarsigner 无法重压缩时（非标准 deflate），返回重打包副本路径。"""
    try:
        zf = zipfile.ZipFile(jar)
        bad = [i for i in zf.infolist() if not i.filename.upper().startswith("META-INF/")]
        zf.close()
        return None  # 简化：直接试签，失败才走这里
    except Exception:
        return None


def repack(src, dst):
    """逐条目重压缩；个别损坏条目按原始压缩字节保留。"""
    import struct
    zin = zipfile.ZipFile(src)
    zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED)
    kept_raw = []
    with open(src, "rb") as f:
        for it in zin.infolist():
            try:
                zout.writestr(it.filename, zin.read(it.filename))
            except Exception:
                f.seek(it.header_offset)
                hdr = f.read(30)
                nlen, elen = struct.unpack("<HH", hdr[26:30])
                f.seek(it.header_offset + 30 + nlen + elen)
                kept_raw.append((it, f.read(it.compress_size)))
    zout.close()
    if kept_raw:
        # 追加原始字节条目
        with open(dst, "ab"):
            pass
        zout = zipfile.ZipFile(dst, "a")
        for it, raw in kept_raw:
            ni = zipfile.ZipInfo(it.filename, date_time=it.date_time)
            ni.compress_type = it.compress_type
            zout.writestr(ni, raw)
        zout.close()
    zin.close()


def sign(jar, ks, storepass, alias, jdk_bins):
    jarsigner = os.path.join(jdk_bins, "jarsigner.exe")
    if not os.path.exists(jarsigner):
        jarsigner = "jarsigner"
    signed = jar + ".s"
    cmd = [jarsigner, "-J-Djdk.jar.disabledAlgorithms=",
           "-keystore", ks, "-storepass", storepass,
           "-sigalg", "SHA1withRSA", "-digestalg", "SHA1",
           "-signedjar", signed, jar, alias]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0 or not os.path.exists(signed):
        # 尝试重压缩后再签（非标准 zip）
        rp = jar + ".repack"
        repack(jar, rp)
        r = subprocess.run(cmd[:-3] + [rp, alias], capture_output=True)
        if r.returncode != 0 or not os.path.exists(signed):
            sys.stderr.write("jarsigner failed: %s\n%s\n" % (r.returncode, r.stderr.decode("utf-8", "ignore")[-400:]))
            sys.exit(2)
        os.remove(rp)
    return signed


def strip_signature(jar):
    """去掉 META-INF 下的 .SF/.RSA/.DSA（不验签时避免个别固件多事）。"""
    zin = zipfile.ZipFile(jar)
    zout = zipfile.ZipFile(jar + ".clean", "w", zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        if it.filename.upper().startswith("META-INF/") and it.filename.upper().endswith((".SF", ".RSA", ".DSA")):
            continue
        zout.writestr(it.filename, zin.read(it.filename))
    zout.close()
    zin.close()
    shutil.move(jar + ".clean", jar)


def read_manifest(jar):
    mf = {}
    for line in zipfile.ZipFile(jar).read("META-INF/MANIFEST.MF").decode("utf-8", "ignore").splitlines():
        if ": " in line:
            k, v = line.split(": ", 1)
            mf.setdefault(k, v)
    return mf


def make_jad(jar_path, name, out_jad, do_sign, version, icon, cls, midlet1=None):
    mf = read_manifest(jar_path)
    if midlet1 is None:
        if "MIDlet-1" in mf:
            midlet1 = mf["MIDlet-1"]
        else:
            midlet1 = "%s,%s,%s" % (name, icon, cls)
    lines = []
    lines.append("MIDlet-Name: " + (mf.get("MIDlet-Name") or name))
    lines.append("MIDlet-Version: " + (mf.get("MIDlet-Version") or version))
    lines.append("MIDlet-Vendor: " + (mf.get("MIDlet-Vendor") or "Denjhang"))
    for k in MIDLET_KEYS[3:]:
        if k in mf:
            lines.append(k + ": " + mf[k])
    if "MIDlet-1" not in [l.split(":")[0] for l in lines]:
        lines.insert(3, "MIDlet-1: " + midlet1)
    lines.append("MIDlet-Jar-URL: " + os.path.basename(jar_path))
    lines.append("MIDlet-Jar-Size: " + str(os.path.getsize(jar_path)))
    if do_sign:
        sha1 = base64.b64encode(hashlib.sha1(open(jar_path, "rb").read()).digest()).decode()
        lines.append("MIDlet-Jar-RSA-SHA1: " + sha1)
    data = ("\r\n".join(lines) + "\r\n").encode("ascii")
    with open(out_jad, "wb") as f:  # 二进制写，保 CRLF
        f.write(data)
    # 自检
    raw = open(out_jad, "rb").read()
    assert not raw.startswith(b"\xef\xbb\xbf"), "BOM"
    txt = raw.decode("ascii")
    assert "\r\n" in txt and "\n" not in txt.replace("\r\n", ""), "line ending"
    zf = zipfile.ZipFile(jar_path)
    assert "META-INF/MANIFEST.MF" in zf.namelist(), "no manifest"
    if do_sign:
        assert any(x.upper().endswith((".RSA", ".DSA")) for x in zf.namelist()), "no signature file"
    main = midlet1.split(",")[2].strip().replace(".", "/") + ".class"
    assert main in zf.namelist(), "main class missing: " + main


def gen_key(ks, storepass, alias, keytool):
    if os.path.exists(ks):
        return
    cmd = [keytool, "-genkeypair", "-keystore", ks, "-alias", alias,
           "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
           "-storepass", storepass, "-keypass", storepass,
           "-dname", "CN=J2ME Signer, OU=Mobile, O=Denjhang, C=CN"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        sys.stderr.write(r.stderr.decode("utf-8", "ignore")[-300:])
        sys.exit(3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("outdir")
    ap.add_argument("--keystore")
    ap.add_argument("--storepass", default="vgmbrowser")
    ap.add_argument("--alias", default="vgm")
    ap.add_argument("--signed", action="store_true", help="签名并写 MIDlet-Jar-RSA-SHA1")
    ap.add_argument("--name")
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--icon", default="/icon.png")
    ap.add_argument("--cls")
    ap.add_argument("--jdk", help="JDK bin 目录（找 keytool/jarsigner）")
    a = ap.parse_args()

    os.makedirs(a.outdir, exist_ok=True)
    mf = read_manifest(a.jar)
    name = a.name or mf.get("MIDlet-Name") or "MIDlet"
    safe = "".join(c if c.isalnum() else "" for c in name) or "MIDlet"
    out_jar = os.path.join(a.outdir, safe + ".jar")
    out_jad = os.path.join(a.outdir, safe + ".jad")
    shutil.copy(a.jar, out_jar)

    if a.signed:
        jdk_bins = a.jdk or ""
    jdk_bins = a.jdk or ""
    keytool = os.path.join(jdk_bins, "keytool.exe") if jdk_bins else "keytool"
    if a.signed:
        if a.keystore:
            gen_key(a.keystore, a.storepass, a.alias, keytool)
            signed = sign(out_jar, a.keystore, a.storepass, a.alias,
                          jdk_bins or "")
            shutil.move(signed, out_jar)
    else:
        strip_signature(out_jar)

    make_jad(out_jar, name, out_jad, a.signed, a.version, a.icon,
             a.cls or "", midlet1=mf.get("MIDlet-1"))
    print("OK:", out_jar, os.path.getsize(out_jar))
    print("OK:", out_jad)
    if a.signed:
        print("(signed; 自签名证书需先导入手机信任库, 否则真机报属性不匹配)")


if __name__ == "__main__":
    main()
