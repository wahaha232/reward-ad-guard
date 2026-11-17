// 手機螢幕開關（exe 版）：只關手機的螢幕面板，手機照常運作，像 scrcpy 的關螢幕。
// 按「關閉螢幕」或「開啟螢幕」，完成後視窗自動關閉，不常駐。
// 手機端小程式 screenoff.dex 內嵌在 exe 裡，需要時自動推上手機。
// adb 先找 exe 旁邊（攜帶版附 platform-tools），再找 PATH / ANDROID_HOME。
// 命令列：手機螢幕開關.exe off | on [手機位址]   （不開視窗，結束代碼 0=成功）
//
// 編譯（Windows 內建 .NET Framework，C# 5）：TOOLS/screen_off/build_exe.ps1
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
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
        if (args.Length >= 1 && (args[0] == "off" || args[0] == "on"))
        {
            string message;
            bool ok = Run(args[0], args.Length > 1 ? args[1] : null, out message);
            Console.WriteLine(message);
            return ok ? 0 : 1;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new SwitchForm());
        return 0;
    }

    // ---------------------------------------------------------------- adb

    static string FindAdb()
    {
        string here = Path.GetDirectoryName(Application.ExecutablePath);
        var list = new System.Collections.Generic.List<string> {
            Path.Combine(here, "adb.exe"),
            Path.Combine(here, @"platform-tools\adb.exe")
        };
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
        {
            try { if (dir.Trim().Length > 0) list.Add(Path.Combine(dir.Trim(), "adb.exe")); } catch { }
        }
        string home = Environment.GetEnvironmentVariable("ANDROID_HOME");
        if (home != null) list.Add(Path.Combine(home, @"platform-tools\adb.exe"));
        list.Add(Path.Combine(StateDir, @"Android\Sdk\platform-tools\adb.exe"));
        list.Add(@"D:\Claude\android-sdk\platform-tools\adb.exe");
        foreach (var c in list) { try { if (File.Exists(c)) return c; } catch { } }
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
            var err = p.StandardError.ReadToEndAsync();
            string output = p.StandardOutput.ReadToEnd();
            if (!p.WaitForExit(60000)) { try { p.Kill(); } catch { } exitCode = -1; return output + " (timeout)"; }
            exitCode = p.ExitCode;
            return output + err.Result;
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

    static string LastDevice()
    {
        try { return File.Exists(DeviceFile) ? File.ReadAllText(DeviceFile).Trim() : ""; } catch { return ""; }
    }

    /// <summary>The phone to use: connected now, else the given/last address reconnected.</summary>
    static string ResolveDevice(string adb, string address, out string problem)
    {
        int code;
        problem = null;
        if (!string.IsNullOrEmpty(address) && address.Contains(":"))
        {
            Adb(adb, "connect " + address, out code);
        }
        var devices = Devices(adb);
        if (devices.Length == 0)
        {
            string last = LastDevice();
            if (last.Length > 0 && last.Contains(":")) { Adb(adb, "connect " + last, out code); devices = Devices(adb); }
        }
        if (devices.Length == 0) { problem = "沒有連線中的手機：請開啟手機的無線偵錯，輸入位址後按「連線」"; return null; }
        if (!string.IsNullOrEmpty(address) && devices.Contains(address)) return address;
        if (devices.Length > 1) { problem = "連著多台手機，請在位址欄輸入要用的那台：" + string.Join(", ", devices); return null; }
        return devices[0];
    }

    /// <summary>Connection check for the window: the phone's name, or null with why.</summary>
    public static string Check(string address, out string problem)
    {
        string adb = FindAdb();
        if (adb == null) { problem = "找不到 adb（攜帶版請保留 platform-tools 資料夾）"; return null; }
        return ResolveDevice(adb, address, out problem);
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
    public static bool Run(string action, string address, out string message)
    {
        string adb = FindAdb();
        if (adb == null) { message = "找不到 adb（攜帶版請保留 platform-tools 資料夾）"; return false; }
        int code;
        string problem;
        string serial = ResolveDevice(adb, address, out problem);
        if (serial == null) { message = problem; return false; }

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
            message = action == "off" ? "螢幕已關閉，手機照常運作" : "螢幕已開啟";
            return true;
        }
        message = "失敗：" + result.Trim();
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

    static readonly Color Back = Color.FromArgb(22, 24, 31);
    static readonly Color Card = Color.FromArgb(32, 35, 45);
    static readonly Color Muted = Color.FromArgb(150, 156, 172);
    static readonly Color Green = Color.FromArgb(52, 199, 89);
    static readonly Color Red = Color.FromArgb(255, 99, 99);
    static readonly Color Blue = Color.FromArgb(30, 136, 229);

    static Font UiFont(float size, FontStyle style)
    {
        return new Font("Microsoft JhengHei UI", size, style);
    }

    static GraphicsPath Rounded(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        int d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    /// <summary>A rounded, owner-drawn button with a small moon or sun glyph.</summary>
    class BigButton : Control
    {
        public Color Fill;
        public bool Moon;
        public string Caption;
        public string Sub;
        bool hover;

        public BigButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            Cursor = Cursors.Hand;
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            var rect = new Rectangle(0, 0, Width - 1, Height - 1);
            Color fill = !Enabled ? Color.FromArgb(55, 58, 68) : hover ? ControlPaint.Light(Fill, 0.15f) : Fill;
            using (var path = Rounded(rect, 16))
            using (var brush = new SolidBrush(fill))
                g.FillPath(brush, path);

            // glyph
            int gx = 22, gy = Height / 2 - 16, gs = 32;
            if (Moon)
            {
                using (var b = new SolidBrush(Color.FromArgb(255, 205, 70))) g.FillEllipse(b, gx, gy, gs, gs);
                using (var b = new SolidBrush(fill)) g.FillEllipse(b, gx + 11, gy - 6, gs, gs);
            }
            else
            {
                using (var b = new SolidBrush(Color.FromArgb(255, 214, 90))) g.FillEllipse(b, gx + 8, gy + 8, 16, 16);
                using (var p = new Pen(Color.FromArgb(255, 214, 90), 3f))
                {
                    for (int i = 0; i < 8; i++)
                    {
                        double a = i * Math.PI / 4;
                        float cx = gx + 16, cy = gy + 16;
                        g.DrawLine(p, cx + (float)(Math.Cos(a) * 12), cy + (float)(Math.Sin(a) * 12),
                            cx + (float)(Math.Cos(a) * 16), cy + (float)(Math.Sin(a) * 16));
                    }
                }
            }
            using (var f = UiFont(14f, FontStyle.Bold))
                TextRenderer.DrawText(g, Caption, f, new Point(66, Height / 2 - 24), Enabled ? Color.White : Muted);
            using (var f = UiFont(9f, FontStyle.Regular))
                TextRenderer.DrawText(g, Sub, f, new Point(68, Height / 2 + 4), Enabled ? Color.FromArgb(215, 220, 232) : Muted);
        }
    }

    class SwitchForm : Form
    {
        readonly Label status = new Label();
        readonly TextBox address = new TextBox();
        readonly Button connect = new Button();
        readonly BigButton offButton = new BigButton();
        readonly BigButton onButton = new BigButton();
        readonly Label result = new Label();
        readonly Timer closeTimer = new Timer();

        public SwitchForm()
        {
            Text = "手機螢幕開關";
            ClientSize = new Size(420, 400);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            TopMost = true;
            BackColor = Back;
            ForeColor = Color.White;
            Font = UiFont(10f, FontStyle.Regular);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var logo = new PictureBox();
            logo.Location = new Point(20, 18);
            logo.Size = new Size(48, 48);
            logo.SizeMode = PictureBoxSizeMode.Zoom;
            try { logo.Image = Icon.ExtractAssociatedIcon(Application.ExecutablePath).ToBitmap(); } catch { }
            Controls.Add(logo);

            var title = new Label();
            title.Text = "手機螢幕開關";
            title.Font = UiFont(16f, FontStyle.Bold);
            title.Location = new Point(78, 16);
            title.AutoSize = true;
            Controls.Add(title);

            var subtitle = new Label();
            subtitle.Text = "只關螢幕面板，手機照常運作、收取照跑";
            subtitle.ForeColor = Muted;
            subtitle.Font = UiFont(9f, FontStyle.Regular);
            subtitle.Location = new Point(80, 46);
            subtitle.AutoSize = true;
            Controls.Add(subtitle);

            var card = new Panel();
            card.Location = new Point(20, 82);
            card.Size = new Size(380, 82);
            card.BackColor = Card;
            Controls.Add(card);

            status.Location = new Point(14, 10);
            status.Size = new Size(352, 22);
            status.ForeColor = Muted;
            status.Text = "● 檢查手機連線中…";
            card.Controls.Add(status);

            address.Location = new Point(14, 42);
            address.Size = new Size(250, 26);
            address.BackColor = Color.FromArgb(22, 24, 31);
            address.ForeColor = Color.White;
            address.BorderStyle = BorderStyle.FixedSingle;
            address.Text = LastDevice();
            card.Controls.Add(address);

            connect.Text = "連線";
            connect.Location = new Point(274, 40);
            connect.Size = new Size(92, 30);
            connect.FlatStyle = FlatStyle.Flat;
            connect.FlatAppearance.BorderColor = Blue;
            connect.ForeColor = Color.White;
            connect.BackColor = Card;
            connect.Click += delegate { CheckConnection(address.Text.Trim()); };
            card.Controls.Add(connect);

            offButton.Fill = Color.FromArgb(48, 52, 66);
            offButton.Moon = true;
            offButton.Caption = "關閉螢幕";
            offButton.Sub = "面板熄滅，手機繼續工作";
            offButton.Location = new Point(20, 180);
            offButton.Size = new Size(380, 70);
            offButton.Click += delegate { Switch("off"); };
            Controls.Add(offButton);

            onButton.Fill = Blue;
            onButton.Moon = false;
            onButton.Caption = "開啟螢幕";
            onButton.Sub = "亮回來（請用這裡，不要按電源鍵）";
            onButton.Location = new Point(20, 262);
            onButton.Size = new Size(380, 70);
            onButton.Click += delegate { Switch("on"); };
            Controls.Add(onButton);

            result.Location = new Point(20, 344);
            result.Size = new Size(380, 44);
            result.ForeColor = Muted;
            result.Font = UiFont(9f, FontStyle.Regular);
            string last = "";
            try { last = File.Exists(ActionFile) ? File.ReadAllText(ActionFile).Trim() : ""; } catch { }
            result.Text = (last == "off" ? "上次：已關閉螢幕。" : last == "on" ? "上次：已開啟螢幕。" : "") +
                "完成後視窗會自動關閉。";
            Controls.Add(result);

            closeTimer.Interval = 2000;
            closeTimer.Tick += delegate { closeTimer.Stop(); Close(); };

            Shown += delegate
            {
                // Opened from a script or a shortcut set to minimized: always show it.
                WindowState = FormWindowState.Normal;
                Activate();
                CheckConnection(address.Text.Trim());
            };
        }

        void Busy(bool busy)
        {
            offButton.Enabled = onButton.Enabled = connect.Enabled = !busy;
            Cursor = busy ? Cursors.WaitCursor : Cursors.Default;
        }

        void CheckConnection(string addr)
        {
            Busy(true);
            status.ForeColor = Muted;
            status.Text = "● 檢查手機連線中…";
            string serial = null, problem = null;
            var worker = new System.ComponentModel.BackgroundWorker();
            worker.DoWork += delegate { serial = Check(addr, out problem); };
            worker.RunWorkerCompleted += delegate
            {
                Busy(false);
                if (serial != null)
                {
                    status.ForeColor = Green;
                    status.Text = "● 已連線：" + serial;
                    if (address.Text.Trim().Length == 0) address.Text = serial;
                }
                else
                {
                    status.ForeColor = Red;
                    status.Text = "● 未連線";
                    result.ForeColor = Red;
                    result.Text = problem;
                }
            };
            worker.RunWorkerAsync();
        }

        void Switch(string action)
        {
            Busy(true);
            result.ForeColor = Muted;
            result.Text = action == "off" ? "正在關閉螢幕…" : "正在開啟螢幕…";
            string message = null;
            bool ok = false;
            string addr = address.Text.Trim();
            var worker = new System.ComponentModel.BackgroundWorker();
            worker.DoWork += delegate { ok = Run(action, addr, out message); };
            worker.RunWorkerCompleted += delegate
            {
                if (ok)
                {
                    result.ForeColor = Green;
                    result.Text = "✔ " + message + "（2 秒後關閉）";
                    closeTimer.Start();
                }
                else
                {
                    Busy(false);
                    result.ForeColor = Red;
                    result.Text = "✘ " + message;
                }
            };
            worker.RunWorkerAsync();
        }
    }
}
