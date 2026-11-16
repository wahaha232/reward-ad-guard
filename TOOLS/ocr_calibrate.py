"""Compare the app's ML Kit OCR with Windows OCR (zh-Hant-TW) on the same screenshots.

The app's "OCR 校正模式" keeps every OCR'd screenshot with what ML Kit read
(files/ocr_calibration/<day>/<stem>.jpg + .tsv). This script pulls them,
reads each image again with the PC collector's Windows OCR, pairs the text
boxes by position and lists ML Kit's look-alike misreads (same length, only
Han characters differ). The result is a report and suggested lines for
app/src/main/assets/ocr_corrections.tsv.

Usage (Windows, phone connected over adb):
    python TOOLS/ocr_calibrate.py --serial 192.168.68.100:5555
    python TOOLS/ocr_calibrate.py --local D:/Claude/scratch/ocrcal   # already pulled

Needs the PC collector checkout (default D:/Claude/pc-collector-clean) for its
Windows OCR wrapper, and the zh-Hant-TW OCR language pack.
"""
from __future__ import annotations

import argparse
import collections
import io
import re
import subprocess
import sys
import tarfile
import unicodedata
from pathlib import Path

PACKAGE = "com.rewardadguard.app"
PUNCT = set("，。！？、,.!?:;()[]{}<>《》「」『』\"'“”‘’·|/\\-_+")


def normalize(text: str) -> str:
    value = unicodedata.normalize("NFKC", text).lower()
    return "".join(ch for ch in value if not ch.isspace() and ch not in PUNCT)


def is_han(ch: str) -> bool:
    return "\u3400" <= ch <= "\u9fff" or "\uf900" <= ch <= "\ufaff"


def pull(serial: str, dest: Path) -> Path:
    cmd = ["adb", "-s", serial, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files/ocr_calibration"]
    data = subprocess.run(cmd, capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        tar.extractall(dest)
    return dest / "files" / "ocr_calibration"


def read_tsv(path: Path):
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t", 4)
        if len(parts) != 5:
            continue
        l, t, r, b = (int(v) for v in parts[:4])
        boxes.append(((l, t, r, b), parts[4]))
    return boxes


def iou(a, b) -> float:
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def phrase_pair(mlkit: str, windows: str):
    """The smallest window (3+ chars) around the differing characters, or None."""
    if len(mlkit) != len(windows) or mlkit == windows:
        return None
    diffs = [i for i, (a, b) in enumerate(zip(mlkit, windows)) if a != b]
    if not all(is_han(mlkit[i]) and is_han(windows[i]) for i in diffs):
        return None
    lo, hi = diffs[0], diffs[-1] + 1
    while hi - lo < 3:
        if hi < len(mlkit):
            hi += 1
        elif lo > 0:
            lo -= 1
        else:
            break
    return mlkit[lo:hi], windows[lo:hi]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", help="adb serial to pull captures from")
    ap.add_argument("--local", help="already pulled capture folder")
    ap.add_argument("--pc-collector", default="D:/Claude/pc-collector-clean")
    ap.add_argument("--out", default="ocr_calibration_report")
    ap.add_argument("--min-count", type=int, default=1)
    args = ap.parse_args()

    sys.path.insert(0, args.pc_collector)
    from reward_collector.ocr import run_windows_ocr  # noqa: E402

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    if args.local:
        root = Path(args.local)
    elif args.serial:
        root = pull(args.serial, out / "pulled")
    else:
        ap.error("--serial or --local is required")

    images = sorted(root.rglob("*.jpg"))
    pairs = collections.Counter()
    examples: dict[tuple[str, str], str] = {}
    compared = same = other = 0
    for image in images:
        tsv = image.with_suffix(".tsv")
        if not tsv.exists():
            continue
        try:
            win = run_windows_ocr(image.resolve())
        except Exception as exc:  # noqa: BLE001 - keep going over a bad image
            print(f"skip {image.name}: {exc}", file=sys.stderr)
            continue
        win_lines = [((l.bounds.x1, l.bounds.y1, l.bounds.x2, l.bounds.y2), l.text) for l in win.lines]
        for box, text in read_tsv(tsv):
            best = max(win_lines, key=lambda w: iou(box, w[0]), default=None)
            if best is None or iou(box, best[0]) < 0.4:
                continue
            a, b = normalize(text), normalize(best[1])
            if not a or not b:
                continue
            compared += 1
            if a == b:
                same += 1
                continue
            pair = phrase_pair(a, b)
            if pair is None:
                other += 1
                continue
            pairs[pair] += 1
            examples.setdefault(pair, f"{image.name}: ML Kit「{text}」/ Windows「{best[1]}」")

    lines = [
        "# OCR 校正報告",
        "",
        f"- 截圖：{len(images)} 張；配對到的文字框：{compared}",
        f"- 兩邊相同：{same}；同長度只差中文字（錯字候選）：{sum(pairs.values())}；其他差異：{other}",
        "",
        "| ML Kit 讀成 | Windows 讀成 | 次數 | 例子 |",
        "|---|---|---|---|",
    ]
    for (wrong, right), count in pairs.most_common():
        lines.append(f"| {wrong} | {right} | {count} | {examples[(wrong, right)]} |")
    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    suggested = [f"{w}\t{r}" for (w, r), c in pairs.most_common() if c >= args.min_count]
    (out / "suggested_corrections.tsv").write_text("\n".join(suggested) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n建議對照（次數 >= {args.min_count}）：{out / 'suggested_corrections.tsv'}")
    print("請人工確認後再加到 app/src/main/assets/ocr_corrections.tsv（Windows OCR 也會錯）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
