import os
import sys
import json
import base64
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

def fetch_gitiles_with_retry(repo_name, max_retries=4):
    url = f"https://android.googlesource.com/{repo_name}/+log/HEAD?format=JSON&n=3"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Android AOSP Scraper)'})
    
    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                raw = resp.read()
                content = raw.decode('utf-8', errors='ignore')
                if content.startswith(")]}'"):
                    content = content[4:]
                return json.loads(content), "ok"
        except urllib.error.HTTPError as e:
            if e.code == 429:
                # Rate limited, back off
                sleep_time = (attempt + 1) * 1.5
                time.sleep(sleep_time)
                continue
            elif e.code == 404:
                # Try master or main explicitly
                for alt_branch in ['master', 'main']:
                    try:
                        alt_url = f"https://android.googlesource.com/{repo_name}/+log/{alt_branch}?format=JSON&n=3"
                        alt_req = urllib.request.Request(alt_url, headers={'User-Agent': 'Mozilla/5.0'})
                        with urllib.request.urlopen(alt_req, timeout=10) as alt_resp:
                            alt_raw = alt_resp.read().decode('utf-8', errors='ignore')
                            if alt_raw.startswith(")]}'"):
                                alt_raw = alt_raw[4:]
                            return json.loads(alt_raw), "ok"
                    except:
                        pass
                return None, f"HTTP_{e.code}"
            else:
                return None, f"HTTP_{e.code}"
        except Exception as e:
            time.sleep(1.0)
            if attempt == max_retries - 1:
                return None, f"error: {str(e)[:50]}"
    return None, "HTTP_429_exhausted"

def main():
    with open('/sdcard/zalorust/repos_scraped_summary.json') as f:
        old_repos = json.load(f)
    old_names = set(r['name'] for r in old_repos)

    with open('/sdcard/zalorust/repos.json') as f:
        all_repos = json.load(f)

    # Candidate categories
    category_prefixes = [
        'platform/frameworks/',
        'platform/packages/apps/',
        'platform/packages/providers/',
        'platform/packages/services/',
        'platform/packages/modules/',
        'platform/system/',
        'platform/tools/',
        'platform/external/skia',
        'platform/external/perfetto',
        'platform/external/chromium_org/third_party/angle',
        'platform/external/vulkan'
    ]

    selected_new = []
    seen = set(old_names)

    for cp in category_prefixes:
        for r in all_repos:
            name = r['name']
            if name not in seen and name.startswith(cp):
                selected_new.append(r)
                seen.add(name)
                if len(selected_new) >= 155:
                    break
        if len(selected_new) >= 155:
            break

    if len(selected_new) < 155:
        for r in all_repos:
            name = r['name']
            if name not in seen and name.startswith('platform/external/'):
                selected_new.append(r)
                seen.add(name)
                if len(selected_new) >= 155:
                    break

    print(f"Targeting {len(selected_new)} BRAND NEW repos with rate-limit handling...")

    results = []

    def scrape_repo(repo_info):
        time.sleep(0.08)  # small jitter
        repo_name = repo_info['name']
        res = {
            "name": repo_name,
            "url": f"https://android.googlesource.com/{repo_name}",
            "status": "pending",
            "latest_commit": None,
            "commit_count": 0,
            "recent_messages": []
        }
        data, status = fetch_gitiles_with_retry(repo_name)
        res["status"] = status
        if data:
            log = data.get('log', [])
            res["commit_count"] = len(log)
            if log:
                top = log[0]
                res["latest_commit"] = {
                    "commit": top.get("commit"),
                    "author": top.get("author", {}).get("name"),
                    "time": top.get("author", {}).get("time"),
                    "message": top.get("message", "").split("\n")[0]
                }
                res["recent_messages"] = [c.get("message", "").split("\n")[0] for c in log]
        return res

    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(scrape_repo, r): r['name'] for r in selected_new}
        count = 0
        for fut in as_completed(futures):
            res = fut.result()
            results.append(res)
            count += 1
            if count % 25 == 0 or count == len(selected_new):
                ok_count_now = sum(1 for x in results if x["status"] == "ok")
                print(f"Scraped {count}/{len(selected_new)} repos... (OK: {ok_count_now})")

    ok_count = sum(1 for x in results if x["status"] == "ok")
    print(f"Completed! Total new repos scraped: {len(results)}, OK: {ok_count}")

    with open('/sdcard/zalorust/repos_batch2_scraped_summary.json', 'w') as out:
        json.dump(results, out, indent=2)

if __name__ == '__main__':
    main()
