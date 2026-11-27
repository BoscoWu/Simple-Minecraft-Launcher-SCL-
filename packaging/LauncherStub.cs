// ============================================================
//  Simple MC Launcher - Windows 单文件启动器
//
//  编译成 SimpleMCLauncher.exe，内部携带：
//    - 应用 jar 与 JavaFX jar
//    - 精简后的 JRE（jlink 产出）
//
//  解压后的最终形态：
//    SimpleMCLauncher.exe     <- 双击运行，唯一需要的文件
//    pyLauncher/              <- 运行时自动生成（配置、游戏、日志）
//
//  运行时解压到 %LOCALAPPDATA%\SimpleMCLauncher\runtime-<hash>，
//  升级后哈希变化会自动换新目录。
// ============================================================
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

static class Program
{
    const string AppName = "SimpleMCLauncher";
    const string MainClass = "com.simplemc.launcher.LauncherApp";
    const string PayloadResource = "payload.zip";

    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            string exePath = Assembly.GetExecutingAssembly().Location;
            string exeDir = Path.GetDirectoryName(exePath);

            byte[] payload = ReadPayload();
            if (payload == null)
            {
                Error("程序包损坏：找不到内嵌数据。\n请重新下载完整文件。");
                return 1;
            }

            string hash = Sha1Short(payload);
            string cacheRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                AppName, "runtime-" + hash);

            string javaExe = Path.Combine(cacheRoot, "jre", "bin", "javaw.exe");
            string appDir = Path.Combine(cacheRoot, "app");

            if (!File.Exists(javaExe))
            {
                Extract(payload, cacheRoot);
                if (!File.Exists(javaExe))
                {
                    Error("解压失败：找不到 " + javaExe);
                    return 1;
                }
            }

            // JavaFX 必须走模块路径，只放 classpath 会报
            // 「缺少 JavaFX 运行时组件」。
            // scl.data.dir 让 pyLauncher 固定落在 exe 旁边。
            string mainJar = Path.Combine(appDir, "SimpleMCLauncher.jar");
            string fxPath = string.Join(";", Directory.GetFiles(appDir, "javafx-*.jar"));
            string dataDir = Path.Combine(exeDir, "pyLauncher");
            string jreDir = Path.Combine(cacheRoot, "jre");

            var sb = new StringBuilder();
            sb.Append("-Dfile.encoding=UTF-8 ");
            sb.Append("-Xmx512m ");
            sb.Append("-XX:+UseSerialGC ");
            sb.Append("\"-Dscl.app.dir=").Append(exeDir).Append("\" ");
            sb.Append("\"-Dscl.data.dir=").Append(dataDir).Append("\" ");
            // 告诉启动器自带的 JRE 在哪；用户没装 Java 时游戏要用它
            sb.Append("\"-Dscl.jre.dir=").Append(jreDir).Append("\" ");
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
                string target = Path.Combine(dest,
                    entry.FullName.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(target));

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
