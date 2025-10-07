//build (publish) it with: dotnet publish -c Release -r win-x64 -p:PublishSingleFile=true --self-contained=false
//in vsc terminal powershell from PS C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\src\main\java\com\example\launcher\FileDistributorLauncher> 

using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows.Forms;

class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        string java = @"C:\Program Files\Java\jdk-21\bin\javaw.exe";
        string jar  = @"C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\file-distributor.jar";

        // Fallback if args were mangled or empty: let user pick files (Unicode-safe)
        string[] cleanArgs = args;
        bool looksMangled(string a)
        {
            if (string.IsNullOrWhiteSpace(a)) return true;
            if (a.Contains('?')) return true;
            try { _ = Path.GetFullPath(a); } catch { return true; }
            return !File.Exists(a);
        }
        if (cleanArgs.Length == 0 || cleanArgs.Any(looksMangled))
        {
            using var ofd = new OpenFileDialog {
                Title = "File Distributor — Select file(s)",
                Multiselect = true,
                CheckFileExists = true
            };
            if (ofd.ShowDialog() != DialogResult.OK) return 0;
            cleanArgs = ofd.FileNames;
        }

        // 🚀 Write all paths to a UTF-8 temp file
        string tempList = Path.Combine(Path.GetTempPath(), "fd_paths_" + Guid.NewGuid().ToString("N") + ".txt");
        File.WriteAllLines(tempList, cleanArgs, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

        // Ensure working dir = JAR folder so config.json is found
        try { Directory.SetCurrentDirectory(Path.GetDirectoryName(jar)!); } catch { }

        var psi = new ProcessStartInfo
        {
            FileName = java,
            UseShellExecute = false
        };

        // (Optional but nice) nudge JVM default charsets toward UTF-8
        psi.ArgumentList.Add("-Dfile.encoding=UTF-8");
        psi.ArgumentList.Add("-Dsun.jnu.encoding=UTF-8");

        psi.ArgumentList.Add("-jar");
        psi.ArgumentList.Add(jar);

        // Pass a SINGLE ASCII arg that points to the UTF-8 list
        psi.ArgumentList.Add("--args-file=" + tempList);

        try { Process.Start(psi); return 0; }
        catch (Exception ex)
        {
            MessageBox.Show($"Failed to start Java:\n{ex.GetType().Name}: {ex.Message}",
                "File Distributor Launcher", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}