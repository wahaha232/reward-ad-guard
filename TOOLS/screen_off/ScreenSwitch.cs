// 手機螢幕開關（exe 版）：只關手機的螢幕面板，手機照常運作，像 scrcpy 的關螢幕。
// 按「關閉螢幕」或「開啟螢幕」，完成後視窗自動關閉，不常駐。
// 手機端小程式 screenoff.dex 內嵌在 exe 裡，需要時自動推上手機。
// 也可用命令列：手機螢幕開關.exe off | on   （不開視窗，結束代碼 0=成功）
//
// 編譯（Windows 內建 .NET Framework，C# 5）：TOOLS/screen_off/build_exe.ps1
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows.Forms;

static class ScreenSwitch
{
    const string Remote = "/data/local/tmp/screenoff.dex";

    static readonly string StateDir =
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
    static readonly string DeviceFile = Path.Combine(StateDir, "screen_off_last_device.txt");
    static readonly string ActionFile = Path.Combine(StateDir, "screen_off_last_action.txt");

    [STAThread]
    static int Main(string[] args)
    {
        if (args.Length == 1 && (args[0] == "off" || args[0] == "on"))
        {
            string message;
            bool ok = Run(args[0], out message);
            Console.WriteLine(message);
            return ok ? 0 : 1;
        }
        Application.EnableVisualStyles();
        Application.Run(new SwitchForm());
        return 0;
    }

    // ---------------------------------------------------------------- adb

    static string FindAdb()
    {
        var candidates = new[] {
            Environment.GetEnvironmentVariable("ANDROID_HOME") == null ? null :
                Path.Combine(Environment.GetEnvironmentVariable("ANDROID_HOME"), @"platform-tools\adb.exe"),
            Path.Combine(StateDir, @"Android\Sdk\platform-tools\adb.exe"),
            @"D:\Claude\android-sdk\platform-tools\adb.exe"
        };
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
        {
            try
            {
                var p = Path.Combine(dir.Trim(), "adb.exe");
                if (File.Exists(p)) return p;
            }
            catch { }
        }
        foreach (var c in candidates) if (c != null && File.Exists(c)) return c;
        return null;
    }

    static string Adb(string adb, string arguments, out int exitCode)
    {
        var psi = new ProcessStartInfo(adb, arguments);
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.StandardOutputEncoding = Encoding.UTF8;
        psi.StandardErrorEncoding = Encoding.UTF8;
        using (var p = Process.Start(psi))
        {
            string output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
            if (!p.WaitForExit(60000)) { try { p.Kill(); } catch { } exitCode = -1; return output + " (timeout)"; }
            exitCode = p.ExitCode;
            return output;
        }
    }

    static string[] Devices(string adb)
    {
        int code;
        return Adb(adb, "devices", out code)
            .Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.EndsWith("\tdevice"))
            .Select(l => l.Split('\t')[0])
            .ToArray();
    }

    static byte[] Dex()
    {
        using (var s = typeof(ScreenSwitch).Assembly.GetManifestResourceStream("screenoff.dex"))
        using (var m = new MemoryStream())
        {
            s.CopyTo(m);
            return m.ToArray();
        }
    }

    /// <summary>Turns the phone's panel off or on. False with the reason on failure.</summary>
    public static bool Run(string action, out string message)
    {
        string adb = FindAdb();
        if (adb == null) { message = "找不到 adb（請安裝 platform-tools 或設定 ANDROID_HOME）"; return false; }
        int code;
        var devices = Devices(adb);
        if (devices.Length == 0 && File.Exists(DeviceFile))
        {
            // Reconnect the phone used last time (wireless debugging).
            string last = File.ReadAllText(DeviceFile).Trim();
            if (last.Length > 0) { Adb(adb, "connect " + last, out code); devices = Devices(adb); }
        }
        if (devices.Length == 0) { message = "沒有連線中的手機（請開無線偵錯並 adb connect）"; return false; }
        if (devices.Length > 1) { message = "連著多台手機：" + string.Join(", ", devices); return false; }
        string serial = devices[0];

        byte[] dex = Dex();
        string remoteSize = Adb(adb, "-s " + serial + " shell stat -c %s " + Remote, out code).Trim();
        if (remoteSize != dex.Length.ToString())
        {
            string tmp = Path.Combine(Path.GetTempPath(), "screenoff.dex");
            File.WriteAllBytes(tmp, dex);
            Adb(adb, "-s " + serial + " push \"" + tmp + "\" " + Remote, out code);
            if (code != 0) { message = "推送小程式到手機失敗"; return false; }
        }
        string result = Adb(adb, "-s " + serial + " shell CLASSPATH=" + Remote + " app_process / ScreenOff " + action, out code);
        if (result.Contains("OK " + action))
        {
            // HyperOS turns the panel on without its backlight (2026-11-17):
            // nudging the brightness and putting it back lights it again.
            if (action == "on") NudgeBrightness(adb, serial);
            try { File.WriteAllText(DeviceFile, serial); File.WriteAllText(ActionFile, action); } catch { }
            message = action == "off"
                ? "螢幕已關閉（" + serial + "），手機照常運作"
                : "螢幕已開啟（" + serial + "）";
            return true;
        }
        message = "失敗（" + serial + "）：" + result.Trim();
        return false;
    }

    static void NudgeBrightness(string adb, string serial)
    {
        int code;
        string pre = "-s " + serial + " shell settings ";
        string level = Adb(adb, pre + "get system screen_brightness", out code).Trim();
        string mode = Adb(adb, pre + "get system screen_brightness_mode", out code).Trim();
        int value;
        if (!int.TryParse(level, out value)) return;
        Adb(adb, pre + "put system screen_brightness_mode 0", out code);
        Adb(adb, pre + "put system screen_brightness " + (value < 235 ? value + 20 : value - 20), out code);
        System.Threading.Thread.Sleep(600);
        Adb(adb, pre + "put system screen_brightness " + value, out code);
        if (mode == "0" || mode == "1") Adb(adb, pre + "put system screen_brightness_mode " + mode, out code);
    }

    // ----------------------------------------------------------------- UI

    class SwitchForm : Form
    {
        readonly Label status = new Label();
        readonly Button offButton;
        readonly Button onButton;
        readonly Timer closeTimer = new Timer();

        public SwitchForm()
        {
            Text = "手機螢幕開關";
            ClientSize = new Size(364, 212);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            TopMost = true;
            Font = new Font("Microsoft JhengHei UI", 11f);

            string device = File.Exists(DeviceFile) ? File.ReadAllText(DeviceFile).Trim() : "（尚未連過）";
            string last = File.Exists(ActionFile) ? File.ReadAllText(ActionFile).Trim() : "";
            status.Location = new Point(16, 12);
            status.Size = new Size(336, 50);
            status.Text = "手機：" + device + "\n" +
                (last == "off" ? "上次：已關閉螢幕" : last == "on" ? "上次：已開啟螢幕" : "上次：無紀錄");
            Controls.Add(status);

            offButton = MakeButton("關閉螢幕", 16, Color.FromArgb(40, 44, 52));
            onButton = MakeButton("開啟螢幕", 192, Color.FromArgb(30, 136, 229));
            offButton.Click += delegate { Switch("off"); };
            onButton.Click += delegate { Switch("on"); };

            var hint = new Label();
            hint.Location = new Point(16, 152);
            hint.Size = new Size(336, 54);
            hint.ForeColor = Color.DimGray;
            hint.Font = new Font("Microsoft JhengHei UI", 9f);
            hint.Text = "關閉後手機照常運作、收取照跑。\n要亮回來請按「開啟螢幕」，不要按手機電源鍵（會休眠鎖定）。";
            Controls.Add(hint);

            closeTimer.Interval = 2000;
            closeTimer.Tick += delegate { closeTimer.Stop(); Close(); };
        }

        Button MakeButton(string text, int x, Color color)
        {
            var b = new Button();
            b.Text = text;
            b.Location = new Point(x, 70);
            b.Size = new Size(156, 70);
            b.BackColor = color;
            b.ForeColor = Color.White;
            b.FlatStyle = FlatStyle.Flat;
            b.Font = new Font("Microsoft JhengHei UI", 13f, FontStyle.Bold);
            Controls.Add(b);
            return b;
        }

        void Switch(string action)
        {
            offButton.Enabled = onButton.Enabled = false;
            status.ForeColor = SystemColors.ControlText;
            status.Text = "執行中…";
            Cursor = Cursors.WaitCursor;
            var worker = new System.ComponentModel.BackgroundWorker();
            string message = null;
            bool ok = false;
            worker.DoWork += delegate { ok = Run(action, out message); };
            worker.RunWorkerCompleted += delegate
            {
                Cursor = Cursors.Default;
                if (ok)
                {
                    status.ForeColor = Color.DarkGreen;
                    status.Text = "✔ " + message;
                    closeTimer.Start();
                }
                else
                {
                    status.ForeColor = Color.Firebrick;
                    status.Text = "✘ " + message;
                    offButton.Enabled = onButton.Enabled = true;
                }
            };
            worker.RunWorkerAsync();
        }
    }
}
