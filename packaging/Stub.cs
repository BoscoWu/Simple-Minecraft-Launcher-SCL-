// ============================================================
//  Simple MC Launcher - 单文件自解压启动器
//
//  编译成 SimpleMCLauncher.exe，内部携带：
//    - 应用 jar 与 JavaFX jar
//    - 精简后的 JRE（jlink 产出）
//
//  首次运行解压到 %LOCALAPPDATA%\SimpleMCLauncher\runtime-<hash>，
//  之后直接复用。pyLauncher 数据目录始终放在 exe 旁边（便携）。
// ============================================================
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

static class Program
{
    const string AppName = "SimpleMCLauncher";
    const string MainClass = "com.simplemc.launcher.LauncherApp";
    const string PayloadResource = "payload.zip";
    const string VersionTag = "@VERSION@";

    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            string exePath = Assembly.GetExecutingAssembly().Location;
            string exeDir = Path.GetDirectoryName(exePath);

            // 1. 读内嵌的 payload
            byte[] payload = ReadPayload();
            if (payload == null)
            {
                Error("程序包损坏：找不到内嵌数据。\n请重新下载完整文件。");
                return 1;
            }

            // 2. 计算缓存目录（按内容哈希，升级后自动换新目录）
            string hash = Sha1Short(payload);
            string cacheRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                AppName, "runtime-" + hash);

            string javaExe = Path.Combine(cacheRoot, "jre", "bin", "javaw.exe");
            string appDir = Path.Combine(cacheRoot, "app");

            // 3. 首次运行解压
            if (!File.Exists(javaExe))
            {
                Extract(payload, cacheRoot);
                if (!File.Exists(javaExe))
                {
                    Error("解压失败：找不到 " + javaExe);
                    return 1;
                }
            }

            // 4. 组装启动命令
            //    JavaFX 必须走模块路径（--module-path + --add-modules），
            //    只放 classpath 会报「缺少 JavaFX 运行时组件」。
            //    -scl.app.dir 让启动器把 pyLauncher 放在 exe 旁边（便携）。
            string mainJar = Path.Combine(appDir, "SimpleMCLauncher.jar");
            string fxPath = string.Join(";", Directory.GetFiles(appDir, "javafx-*.jar"));

            var sb = new StringBuilder();
            sb.Append("-Dfile.encoding=UTF-8 ");
            sb.Append("-Xmx512m ");
            sb.Append("-XX:+UseSerialGC ");
            sb.Append("\"-Dscl.app.dir=").Append(exeDir).Append("\" ");
            sb.Append("--module-path \"").Append(fxPath).Append("\" ");
            sb.Append("--add-modules javafx.controls,javafx.graphics,javafx.base ");
            sb.Append("-cp \"").Append(mainJar).Append("\" ");
            sb.Append(MainClass);

            var psi = new ProcessStartInfo(javaExe, sb.ToString());
            psi.UseShellExecute = false;
            psi.WorkingDirectory = exeDir;

            var p = Process.Start(psi);
            return p == null ? 1 : 0;
        }
        catch (Exception ex)
        {
            Error("启动失败：\n" + ex.Message + "\n\n" + ex.StackTrace);
            return 1;
        }
    }

    static byte[] ReadPayload()
    {
        var asm = Assembly.GetExecutingAssembly();
        using (var s = asm.GetManifestResourceStream(PayloadResource))
        {
            if (s == null) return null;
            using (var ms = new MemoryStream())
            {
                s.CopyTo(ms);
                return ms.ToArray();
            }
        }
    }

    static string Sha1Short(byte[] data)
    {
        using (var sha = SHA1.Create())
        {
            byte[] h = sha.ComputeHash(data);
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.Append(h[i].ToString("x2"));
            return sb.ToString();
        }
    }

    static void Extract(byte[] payload, string dest)
    {
        Directory.CreateDirectory(dest);
        using (var ms = new MemoryStream(payload))
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Read))
        {
            foreach (var entry in zip.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue;
                string target = Path.Combine(dest, entry.FullName.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(target));

                // 让 shell 脚本保持可执行位（虽然 Windows 用不到，但一致性更好）
                using (var src = entry.Open())
                using (var dst = File.Create(target))
                {
                    src.CopyTo(dst);
                }
            }
        }
    }

    static void Error(string msg)
    {
        MessageBox.Show(msg, "极简 MC 启动器", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
