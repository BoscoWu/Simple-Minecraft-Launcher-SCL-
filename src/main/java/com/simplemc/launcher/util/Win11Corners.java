package com.simplemc.launcher.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows 窗口圆角（Win11）/ 直角（Win10）。
 *
 * <p>JavaFX 的 {@code StageStyle.UNDECORATED} 窗口默认直角，
 * 需要 DWM 的 {@code DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE)}。</p>
 *
 * <p>句柄获取：JavaFX 的 {@code getPeer()} 在部分版本返回 null，反射不稳定。
 * 这里用 PowerShell + P/Invoke 按「进程 ID」枚举本进程的顶层窗口，
 * 取面积最大的作为主窗口，再设置圆角。不引入依赖，也不需要 --enable-preview。</p>
 */
public final class Win11Corners {

    /** DWMWCP_ROUND */
    public static final int ROUND = 2;
    /** DWMWCP_DONOTROUND */
    public static final int SQUARE = 1;

    private Win11Corners() {}

    /**
     * 给当前进程的主窗口设置圆角偏好。
     *
     * @param preference 2 = 圆角，1 = 直角
     * @return 成功返回窗口句柄，失败返回 0
     */
    public static long applyToMainWindow(int preference) {
        if (!Constants.isWindows()) return 0;

        long pid = ProcessHandle.current().pid();
        String script = buildScript(pid, preference);

        try {
            Path scriptFile = Constants.DATA_DIR.resolve("set_corners.ps1");
            Files.createDirectories(Constants.DATA_DIR);
            Files.writeString(scriptFile, script,
                    java.nio.charset.StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);

            long hwnd = 0;
            boolean ok = false;
            for (String part : out.split("[\\s|]+")) {
                if (part.startsWith("hwnd=")) {
                    try {
                        hwnd = Long.parseLong(part.substring(5));
                    } catch (NumberFormatException ignored) {
                    }
                } else if (part.startsWith("hr=")) {
                    ok = "0".equals(part.substring(3));
                }
            }
            if (!ok) {
                Log.warn("圆角脚本输出: " + out.replace('\n', ' '));
            }
            return ok ? hwnd : 0;
        } catch (Exception e) {
            Log.warn("设置窗口圆角失败: " + e.getMessage());
            return 0;
        }
    }

    private static String buildScript(long pid, int preference) {
        return ""
            + "$ErrorActionPreference = 'Stop'\n"
            + "$src = @'\n"
            + "using System;\n"
            + "using System.Runtime.InteropServices;\n"
            + "public class SclCorners {\n"
            + "    [StructLayout(LayoutKind.Sequential)]\n"
            + "    public struct R { public int l, t, r, b; }\n"
            + "    public delegate bool EnumProc(IntPtr h, IntPtr p);\n"
            + "    [DllImport(\"user32.dll\")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);\n"
            + "    [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);\n"
            + "    [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr h);\n"
            + "    [DllImport(\"user32.dll\")] public static extern bool GetWindowRect(IntPtr h, out R r);\n"
            + "    [DllImport(\"dwmapi.dll\")] public static extern int DwmSetWindowAttribute(IntPtr h, int a, ref int v, int s);\n"
            + "    public static IntPtr FindMain(uint target) {\n"
            + "        IntPtr best = IntPtr.Zero; int area = 0;\n"
            + "        EnumWindows((h, p) => {\n"
            + "            uint q; GetWindowThreadProcessId(h, out q);\n"
            + "            if (q != target || !IsWindowVisible(h)) return true;\n"
            + "            R r; GetWindowRect(h, out r);\n"
            + "            int a = (r.r - r.l) * (r.b - r.t);\n"
            + "            if (a > area) { area = a; best = h; }\n"
            + "            return true;\n"
            + "        }, IntPtr.Zero);\n"
            + "        return best;\n"
            + "    }\n"
            + "    public static int SetCorner(IntPtr h, int pref) {\n"
            + "        int v = pref;\n"
            + "        return DwmSetWindowAttribute(h, 33, ref v, 4);\n"
            + "    }\n"
            + "}\n"
            + "'@\n"
            + "if (-not ('SclCorners' -as [type])) { Add-Type -TypeDefinition $src }\n"
            + "$h = [SclCorners]::FindMain(" + pid + ")\n"
            + "if ($h -eq [IntPtr]::Zero) {\n"
            + "    Write-Output 'hwnd=0 hr=-1'\n"
            + "    exit 0\n"
            + "}\n"
            + "$hr = [SclCorners]::SetCorner($h, " + preference + ")\n"
            + "Write-Output ('hwnd=' + $h.ToInt64() + ' hr=' + $hr)\n";
    }

    /** 按平台设置：Win11 圆角，Win10 直角 */
    public static void applyByPlatform() {
        if (!Constants.isWindows()) return;
        boolean wantRound = Constants.useRoundedCorners();
        long hwnd = applyToMainWindow(wantRound ? ROUND : SQUARE);
        if (hwnd != 0) {
            Log.info(wantRound ? "已启用 Windows 11 窗口圆角"
                    : "Windows 10：使用直角窗口");
        } else {
            Log.warn("窗口圆角设置未生效");
        }
    }
}
