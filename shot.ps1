# 抓取 KEmulator 窗口截图（不要求窗口在前台）
# 用法: powershell -NoProfile -File ./shot.ps1 [-Out shot_game.png] [-Title Brick]
param(
    [string]$Out = "shot.png",
    [string]$Title = ""
)

Add-Type -AssemblyName System.Drawing

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Drawing;

public class KemShot {
    public delegate bool EnumProc(IntPtr h, IntPtr l);

    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);

    public struct RECT { public int L, T, R, B; }

    public static IntPtr Find(uint pid, string title) {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            uint p;
            GetWindowThreadProcessId(h, out p);
            if (p == pid && IsWindowVisible(h)) {
                StringBuilder sb = new StringBuilder(512);
                GetWindowTextW(h, sb, 512);
                string t = sb.ToString();
                RECT r;
                GetWindowRect(h, out r);
                int w = r.R - r.L, ht = r.B - r.T;
                // 跳过 0x0 和过小的辅助窗口
                if (w > 80 && ht > 80 && (title.Length == 0 || t.Contains(title))) {
                    found = h;
                    return false;
                }
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static string Grab(IntPtr h, string path) {
        RECT r;
        GetWindowRect(h, out r);
        int w = r.R - r.L, ht = r.B - r.T;
        using (Bitmap b = new Bitmap(w, ht))
        using (Graphics g = Graphics.FromImage(b)) {
            IntPtr dc = g.GetHdc();
            // 2 = PW_RENDERFULLCONTENT，对 SWT 窗口比 0 可靠
            PrintWindow(h, dc, 2);
            g.ReleaseHdc(dc);
            b.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
        return w + "x" + ht;
    }
}
'@

Add-Type -TypeDefinition $src -ReferencedAssemblies System.Drawing

$procs = Get-Process java -ErrorAction SilentlyContinue
if (-not $procs) {
    Write-Error "没有找到 java 进程（KEmulator 没在运行？）"
    exit 1
}

foreach ($p in $procs) {
    $h = [KemShot]::Find([uint32]$p.Id, $Title)
    if ($h -ne [IntPtr]::Zero) {
        $full = [System.IO.Path]::GetFullPath($Out)
        $size = [KemShot]::Grab($h, $full)
        Write-Output "saved $full ($size) from PID $($p.Id)"
        exit 0
    }
}

Write-Error "没找到匹配的 KEmulator 窗口"
exit 1
