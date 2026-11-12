#!/usr/bin/env python3
"""
aosp_repo_crawler.py - liệt kê toàn bộ repository trên một máy chủ Gitiles
(mặc định: https://android.googlesource.com/).

Đặc điểm
  * Chỉ dùng thư viện chuẩn Python 3.8+, không cần pip install.
  * Ưu tiên endpoint chính thức  ?format=JSON  (ổn định hơn parse HTML);
    nếu máy chủ không trả JSON thì tự chuyển sang parse HTML.
  * Tôn trọng robots.txt, giới hạn tốc độ, tự thử lại khi gặp 429/5xx.
  * Xuất: repos.txt (mỗi dòng một URL), repos.csv, repos.json,
    và tùy chọn clone_all.sh (script git clone).

Ví dụ
  python3 aosp_repo_crawler.py
  python3 aosp_repo_crawler.py --filter '^platform/frameworks/' --out frameworks
  python3 aosp_repo_crawler.py --refs --delay 1.5          # kèm danh sách nhánh/tag từng repo
  python3 aosp_repo_crawler.py --clone-script --depth 1    # sinh clone_all.sh
"""
import argparse
import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import urllib.robotparser
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse

DEFAULT_BASE = "https://android.googlesource.com/"
USER_AGENT = "aosp-repo-crawler/1.0 (personal use; respects robots.txt)"


# --------------------------------------------------------------------------- HTTP
class Fetcher:
    def __init__(self, delay=1.0, retries=4, timeout=60):
        self.delay, self.retries, self.timeout = delay, retries, timeout
        self._last = 0.0
        self.robots = None

    def _sleep_for_rate_limit(self):
        wait = self.delay - (time.time() - self._last)
        if wait > 0:
            time.sleep(wait)
        self._last = time.time()

    def get(self, url):
        last_err = None
        for attempt in range(1, self.retries + 1):
            self._sleep_for_rate_limit()
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity"})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return r.read().decode(r.headers.get_content_charset() or "utf-8", "replace")
            except urllib.error.HTTPError as e:
                last_err = e
                if e.code in (429, 500, 502, 503, 504):
                    retry_after = e.headers.get("Retry-After")
                    pause = int(retry_after) if retry_after and retry_after.isdigit() else 2 ** attempt
                    print(f"  HTTP {e.code}, thử lại sau {pause}s ({attempt}/{self.retries})", file=sys.stderr)
                    time.sleep(pause)
                    continue
                raise
            except (urllib.error.URLError, TimeoutError) as e:
                last_err = e
                time.sleep(2 ** attempt)
        raise RuntimeError(f"Không tải được {url}: {last_err}")

    def load_robots(self, base):
        rp = urllib.robotparser.RobotFileParser()
        try:
            text = self.get(urljoin(base, "/robots.txt"))
            rp.parse(text.splitlines())
        except urllib.error.HTTPError as e:
            if 400 <= e.code < 500:      # không có robots.txt => được phép
                rp.parse([])
            else:
                raise
        except Exception as e:            # lỗi mạng: cứ cảnh báo rồi tiếp tục thận trọng
            print(f"Cảnh báo: không đọc được robots.txt ({e})", file=sys.stderr)
            rp.parse([])
        self.robots = rp

    def allowed(self, url):
        return self.robots is None or self.robots.can_fetch(USER_AGENT, url)


# --------------------------------------------------------------------------- Parsers
def parse_json_index(text, base):
    """Gitiles host index JSON: ")]}'\\n{ "<repo>": {"name":..., "clone_url":..., "description":...}, ... }"""
    text = text.lstrip()
    if text.startswith(")]}'"):
        text = text[4:]
    data = json.loads(text)
    repos = []
    for name, info in data.items():
        info = info if isinstance(info, dict) else {}
        repos.append({
            "name": info.get("name", name),
            "url": urljoin(base, name.strip("/") + "/"),
            "clone_url": info.get("clone_url") or urljoin(base, name.strip("/")),
            "description": (info.get("description") or "").strip(),
        })
    return repos


class _RepoListHTML(HTMLParser):
    """Đọc trang chỉ mục Gitiles: <a class="RepoList-itemName" href=...> + <span class="RepoList-itemDescription">"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.items, self._cur, self._in_name, self._in_desc = [], None, False, False
        self.generic = []          # dự phòng: mọi liên kết tương đối dạng /a/b/

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        cls = a.get("class", "") or ""
        if tag == "a" and a.get("href"):
            href = a["href"]
            if "RepoList-itemName" in cls:
                self._cur = {"href": href, "name": "", "description": ""}
                self.items.append(self._cur)
                self._in_name = True
            elif href.startswith("/") and href.endswith("/") and "?" not in href and not href.startswith("/+"):
                self.generic.append(href)
        elif tag in ("span", "div") and "RepoList-itemDescription" in cls and self._cur is not None:
            self._in_desc = True

    def handle_endtag(self, tag):
        if tag == "a":
            self._in_name = False
        elif tag in ("span", "div"):
            self._in_desc = False

    def handle_data(self, data):
        if self._cur is None:
            return
        if self._in_name:
            self._cur["name"] += data
        elif self._in_desc:
            self._cur["description"] += data


def parse_html_index(text, base):
    p = _RepoListHTML()
    p.feed(text)
    repos = []
    if p.items:
        for it in p.items:
            path = urlparse(urljoin(base, it["href"])).path.strip("/")
            repos.append({
                "name": it["name"].strip() or path,
                "url": urljoin(base, it["href"]),
                "clone_url": urljoin(base, path),
                "description": it["description"].strip(),
            })
    else:  # trang đổi giao diện: lấy mọi liên kết dạng /<repo>/
        skip = {"/login/", "/new-password/", "/"}
        for href in dict.fromkeys(p.generic):
            if href in skip:
                continue
            path = href.strip("/")
            repos.append({"name": path, "url": urljoin(base, href),
                          "clone_url": urljoin(base, path), "description": ""})
    return repos


def parse_refs_json(text):
    text = text.lstrip()
    if text.startswith(")]}'"):
        text = text[4:]
    data = json.loads(text)
    return sorted(data.keys()) if isinstance(data, dict) else []


# --------------------------------------------------------------------------- Main flow
def list_repos(fetcher, base):
    json_url = urljoin(base, "?format=JSON")
    if fetcher.allowed(json_url):
        try:
            txt = fetcher.get(json_url)
            repos = parse_json_index(txt, base)
            if repos:
                print(f"Đọc JSON: {len(repos)} repository")
                return repos
        except Exception as e:
            print(f"JSON không dùng được ({e}); chuyển sang HTML", file=sys.stderr)
    html_url = urljoin(base, "?format=HTML")
    if not fetcher.allowed(html_url):
        raise SystemExit("robots.txt không cho phép tải trang chỉ mục. Hãy dùng công cụ 'repo' với manifest chính thức: repo init -u https://android.googlesource.com/platform/manifest")
    repos = parse_html_index(fetcher.get(html_url), base)
    print(f"Đọc HTML: {len(repos)} repository")
    return repos


def write_outputs(repos, out, clone_script, depth):
    with open(out + ".txt", "w", encoding="utf-8") as f:
        f.write("\n".join(r["url"] for r in repos) + "\n")
    with open(out + ".csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["name", "url", "clone_url", "description"] + (["refs"] if repos and "refs" in repos[0] else []))
        w.writeheader()
        for r in repos:
            row = dict(r)
            if "refs" in row:
                row["refs"] = ";".join(row["refs"])
            w.writerow(row)
    with open(out + ".json", "w", encoding="utf-8") as f:
        json.dump(repos, f, ensure_ascii=False, indent=2)
    written = [out + ".txt", out + ".csv", out + ".json"]
    if clone_script:
        path = "clone_all.sh"
        with open(path, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n# CẢNH BÁO: toàn bộ AOSP rất lớn (hàng trăm GB tới TB). Hãy lọc bớt bằng --filter.\nset -u\n")
            d = f"--depth {depth} " if depth else ""
            for r in repos:
                name = r["name"].replace("'", "")
                f.write(f"[ -d '{name}' ] || git clone {d}'{r['clone_url']}' '{name}'\n")
        os.chmod(path, 0o755)
        written.append(path)
    return written


def main():
    ap = argparse.ArgumentParser(description="Liệt kê repository của một máy chủ Gitiles (AOSP).")
    ap.add_argument("--base", default=DEFAULT_BASE, help="URL gốc (mặc định: android.googlesource.com)")
    ap.add_argument("--out", default="repos", help="tiền tố file xuất (mặc định: repos)")
    ap.add_argument("--filter", help="regex lọc theo tên repo, ví dụ '^platform/frameworks/'")
    ap.add_argument("--delay", type=float, default=1.0, help="giây giữa các request (mặc định 1.0)")
    ap.add_argument("--refs", action="store_true", help="lấy thêm danh sách nhánh/tag từng repo (chậm: 1 request/repo)")
    ap.add_argument("--limit", type=int, help="chỉ xử lý N repo đầu (để thử)")
    ap.add_argument("--clone-script", action="store_true", help="sinh clone_all.sh")
    ap.add_argument("--depth", type=int, default=1, help="độ sâu clone trong script (0 = đầy đủ)")
    args = ap.parse_args()

    base = args.base if args.base.endswith("/") else args.base + "/"
    fetcher = Fetcher(delay=args.delay)
    fetcher.load_robots(base)

    repos = list_repos(fetcher, base)
    if args.filter:
        rx = re.compile(args.filter)
        repos = [r for r in repos if rx.search(r["name"])]
        print(f"Sau khi lọc: {len(repos)} repository")
    if args.limit:
        repos = repos[: args.limit]

    if args.refs:
        for i, r in enumerate(repos, 1):
            url = urljoin(r["url"], "+refs?format=JSON")
            try:
                r["refs"] = parse_refs_json(fetcher.get(url)) if fetcher.allowed(url) else []
            except Exception as e:
                print(f"  [{i}/{len(repos)}] {r['name']}: bỏ qua refs ({e})", file=sys.stderr)
                r["refs"] = []
            if i % 25 == 0 or i == len(repos):
                print(f"  refs {i}/{len(repos)}")

    for p in write_outputs(repos, args.out, args.clone_script, args.depth):
        print("Đã ghi:", p)


if __name__ == "__main__":
    main()
