using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: AssemblyTitle("LAN Exam Client")]
[assembly: AssemblyDescription("Portable LAN Exam Client with its own Java runtime")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

internal static class PortableClient
{
    private const string PayloadHash = "__PAYLOAD_HASH__";
    private const string LauncherName = "LANExamClient.exe";

    [STAThread]
    private static int Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LANExamClient", "runtime");
            string image = Path.Combine(root, PayloadHash);
            if (!IsReady(image))
            {
                Exception preparationError = null;
                using (Form splash = new Form())
                {
                    splash.Text = "LAN Exam Client";
                    splash.ClientSize = new Size(460, 110);
                    splash.StartPosition = FormStartPosition.CenterScreen;
                    splash.FormBorderStyle = FormBorderStyle.FixedDialog;
                    splash.MaximizeBox = false;
                    splash.MinimizeBox = false;
                    splash.ControlBox = false;
                    Label label = new Label();
                    label.Text = "Đang chuẩn bị ứng dụng cho lần mở đầu...";
                    label.AutoSize = false;
                    label.SetBounds(20, 22, 420, 30);
                    ProgressBar progress = new ProgressBar();
                    progress.Style = ProgressBarStyle.Marquee;
                    progress.SetBounds(20, 65, 420, 20);
                    splash.Controls.Add(label);
                    splash.Controls.Add(progress);
                    splash.Shown += delegate
                    {
                        Task.Factory.StartNew(delegate
                        {
                            try { Prepare(root, image); }
                            catch (Exception ex) { preparationError = ex; }
                            finally { splash.BeginInvoke((Action)delegate { splash.Close(); }); }
                        });
                    };
                    Application.Run(splash);
                }
                if (preparationError != null) throw preparationError;
            }
            ProcessStartInfo info = new ProcessStartInfo();
            info.FileName = Path.Combine(image, LauncherName);
            info.WorkingDirectory = image;
            info.UseShellExecute = false;
            using (Process client = Process.Start(info))
            {
                if (client == null) throw new IOException("Không mở được giao diện Client.");
                client.WaitForExit();
                if (client.ExitCode != 0)
                    throw new IOException("Client đã dừng với mã lỗi " + client.ExitCode + ". Hãy gửi thông báo này cho người cung cấp ứng dụng.");
            }
            return 0;
        }
        catch (Exception ex)
        {
            MessageBox.Show("Không thể mở LAN Exam Client.\n\n" + ex.Message, "LAN Exam Client", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static bool IsReady(string image)
    {
        string marker = Path.Combine(image, ".ready");
        return File.Exists(marker) && File.ReadAllText(marker) == PayloadHash
            && File.Exists(Path.Combine(image, LauncherName))
            && File.Exists(Path.Combine(image, "runtime", "bin", "server", "jvm.dll"))
            && File.Exists(Path.Combine(image, "app", "lan-exam-client.jar"));
    }

    private static void Prepare(string root, string image)
    {
        Directory.CreateDirectory(root);
        // A file lock coordinates simultaneous first launches for this Windows user.
        string lockPath = Path.Combine(root, PayloadHash + ".lock");
        FileStream gate = null;
        DateTime deadline = DateTime.UtcNow.AddMinutes(3);
        while (gate == null)
        {
            try { gate = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
            catch (IOException)
            {
                if (DateTime.UtcNow >= deadline) throw new IOException("Một cửa sổ khác đang chuẩn bị Client. Hãy thử mở lại sau.");
                Thread.Sleep(150);
            }
        }
        using (gate)
        {
            if (IsReady(image)) return;
            string staging = Path.Combine(root, PayloadHash + ".preparing-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(staging);
                using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream("ClientPayload"))
                {
                    if (payload == null) throw new IOException("File EXE thiếu dữ liệu ứng dụng.");
                    using (SHA256 sha = SHA256.Create())
                    {
                        string actual = BitConverter.ToString(sha.ComputeHash(payload)).Replace("-", "").ToLowerInvariant();
                        if (actual != PayloadHash) throw new IOException("File EXE không còn nguyên vẹn. Hãy nhận lại bản gốc.");
                    }
                    payload.Position = 0;
                    using (ZipArchive zip = new ZipArchive(payload, ZipArchiveMode.Read))
                    {
                        string prefix = Path.GetFullPath(staging) + Path.DirectorySeparatorChar;
                        foreach (ZipArchiveEntry entry in zip.Entries)
                        {
                            string destination = Path.GetFullPath(Path.Combine(staging, entry.FullName));
                            if (!destination.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                                throw new IOException("Đường dẫn trong gói ứng dụng không hợp lệ.");
                            if (String.IsNullOrEmpty(entry.Name)) Directory.CreateDirectory(destination);
                            else
                            {
                                Directory.CreateDirectory(Path.GetDirectoryName(destination));
                                using (Stream input = entry.Open())
                                using (FileStream output = File.Create(destination)) input.CopyTo(output);
                            }
                        }
                    }
                }
                File.WriteAllText(Path.Combine(staging, ".ready"), PayloadHash);
                if (Directory.Exists(image))
                    Directory.Move(image, image + ".previous-" + Guid.NewGuid().ToString("N"));
                Directory.Move(staging, image);
            }
            finally
            {
                string prefix = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
                if (Path.GetFullPath(staging).StartsWith(prefix, StringComparison.OrdinalIgnoreCase) && Directory.Exists(staging))
                    Directory.Delete(staging, true);
            }
        }
    }
}
