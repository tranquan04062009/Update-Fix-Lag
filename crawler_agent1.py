#!/usr/bin/env python3
"""
crawler_agent1.py - Deep Gitiles crawler & analyzer for Agent 1 (Kernel & Low-Level Hardware Systems)
Queries android.googlesource.com for 132 selected repositories, extracts commit histories,
reads key source files (lmkd, Power HAL, Thermal HAL, BPF, Meminfo), and combines findings
with Qualcomm Snapdragon 750G (SM7225 / Adreno 619 / Android 13 One UI 5.1) tuning specs.
"""

import os
import sys
import json
import time
import base64
import urllib.request
import urllib.error
import concurrent.futures

BASE_GITILES = "https://android.googlesource.com/"
USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-A426N) AppleWebKit/537.36 Agent1-Research/1.0"

KEYWORDS = [
    'zram', 'swap', 'compaction', 'reclaim', 'lmkd', 'psi', 'thrashing',
    'thermal', 'cooling', 'power', 'boost', 'cpufreq', 'schedutil', 'walt',
    'eas', 'adreno', 'kgsl', 'gpu', 'vulkan', 'angle', 'bpf', 'cgroup',
    'freezer', 'madvise', 'ion', 'dmabuf', 'dirty_ratio', 'swappiness',
    'page-cluster', 'sched_util_clamp', 'core_ctl', 'heaviest_task'
]

def fetch_json(url, timeout=12):
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
            if data.startswith(b")]}'\n"):
                data = data[5:]
            return json.loads(data.decode('utf-8'))
    except Exception as e:
        return None

def fetch_text_b64(url, timeout=12):
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return base64.b64decode(raw).decode('utf-8', errors='ignore')
    except Exception as e:
        return None

def crawl_single_repo(repo_info):
    name = repo_info['name']
    result = {
        'name': name,
        'url': f"{BASE_GITILES}{name}/",
        'status': 'UNKNOWN',
        'ref': None,
        'latest_commit': None,
        'recent_commits': [],
        'detected_keywords': set(),
        'error': None
    }

    # Try refs: HEAD, master, main
    for ref in ['HEAD', 'master', 'main']:
        log_url = f"{BASE_GITILES}{name}/+log/{ref}?format=JSON&n=8"
        j = fetch_json(log_url)
        if j and 'log' in j:
            result['status'] = 'OK'
            result['ref'] = ref
            logs = j.get('log', [])
            if logs:
                first = logs[0]
                result['latest_commit'] = {
                    'commit': first.get('commit'),
                    'author': first.get('author', {}).get('name'),
                    'time': first.get('committer', {}).get('time'),
                    'message': first.get('message', '').strip()
                }
                for c in logs:
                    msg = c.get('message', '')
                    first_line = msg.split('\n')[0] if msg else ''
                    result['recent_commits'].append({
                        'commit': c.get('commit'),
                        'subject': first_line
                    })
                    msg_lower = msg.lower()
                    for kw in KEYWORDS:
                        if kw in msg_lower:
                            result['detected_keywords'].add(kw)
            break

    if result['status'] == 'UNKNOWN':
        result['status'] = 'UNAVAILABLE'

    result['detected_keywords'] = sorted(list(result['detected_keywords']))
    return result

def fetch_specialized_sources():
    print("Fetching specialized source files from AOSP...")
    sources = {}

    # 1. lmkd.cpp
    lmkd_url = f"{BASE_GITILES}platform/system/memory/lmkd/+/refs/heads/master/lmkd.cpp?format=TEXT"
    lmkd_cpp = fetch_text_b64(lmkd_url)
    if lmkd_cpp:
        sources['lmkd_cpp_length'] = len(lmkd_cpp)
        # extract persist.device_config props
        lmkd_props = []
        for line in lmkd_cpp.splitlines():
            if 'persist.device_config.lmkd_native.' in line or 'ro.lmk.' in line:
                lmkd_props.append(line.strip())
        sources['lmkd_extracted_props'] = lmkd_props[:30]

    # 2. Power Mode.aidl & Boost.aidl
    mode_url = f"{BASE_GITILES}platform/hardware/interfaces/+/refs/heads/master/power/aidl/aidl_api/android.hardware.power/current/android/hardware/power/Mode.aidl?format=TEXT"
    mode_aidl = fetch_text_b64(mode_url)
    if mode_aidl:
        sources['power_mode_aidl'] = mode_aidl

    boost_url = f"{BASE_GITILES}platform/hardware/interfaces/+/refs/heads/master/power/aidl/aidl_api/android.hardware.power/current/android/hardware/power/Boost.aidl?format=TEXT"
    boost_aidl = fetch_text_b64(boost_url)
    if boost_aidl:
        sources['power_boost_aidl'] = boost_aidl

    # 3. Thermal IThermal.aidl & types
    thermal_url = f"{BASE_GITILES}platform/hardware/interfaces/+/refs/heads/master/thermal/aidl/aidl_api/android.hardware.thermal/current/android/hardware/thermal/IThermal.aidl?format=TEXT"
    thermal_aidl = fetch_text_b64(thermal_url)
    if thermal_aidl:
        sources['thermal_aidl'] = thermal_aidl

    # 4. sysmeminfo.h
    meminfo_url = f"{BASE_GITILES}platform/system/memory/libmeminfo/+/refs/heads/master/include/meminfo/sysmeminfo.h?format=TEXT"
    meminfo_h = fetch_text_b64(meminfo_url)
    if meminfo_h:
        sources['sysmeminfo_h_snippet'] = meminfo_h[:2000]

    # 5. BpfLoader.cpp
    bpf_url = f"{BASE_GITILES}platform/system/bpf/+/refs/heads/master/bpfloader/BpfLoader.cpp?format=TEXT"
    bpf_cpp = fetch_text_b64(bpf_url)
    if bpf_cpp:
        sources['bpfloader_cpp_snippet'] = bpf_cpp[:2500]

    return sources

def main():
    repo_file = '/sdcard/zalorust/agent1_selected_repos.json'
    if not os.path.exists(repo_file):
        print(f"Error: {repo_file} not found.")
        sys.exit(1)

    with open(repo_file, 'r') as f:
        repos = json.load(f)

    print(f"Starting crawl of {len(repos)} selected repositories...")
    start_time = time.time()

    crawled_results = []
    # Use ThreadPoolExecutor with 10 workers for balanced speed and courtesy
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        future_to_repo = {executor.submit(crawl_single_repo, r): r for r in repos}
        completed = 0
        for future in concurrent.futures.as_completed(future_to_repo):
            completed += 1
            res = future.result()
            crawled_results.append(res)
            if completed % 20 == 0 or completed == len(repos):
                print(f"Progress: {completed}/{len(repos)} repositories crawled ({time.time()-start_time:.1f}s)...")

    # Sort results by name
    crawled_results.sort(key=lambda x: x['name'])
    ok_count = sum(1 for r in crawled_results if r['status'] == 'OK')
    print(f"Crawl completed: {ok_count}/{len(repos)} repositories successfully queried in {time.time()-start_time:.1f}s")

    # Fetch specialized sources
    sources = fetch_specialized_sources()

    # Package findings
    findings = {
        'meta': {
            'agent': 'Agent 1 - Kernel & Low-Level Hardware Systems',
            'timestamp': time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime()),
            'target_device': 'Samsung Galaxy A42 5G (SM-A426N/B)',
            'soc': 'Qualcomm Snapdragon 750G 5G (SM7225, 8nm)',
            'cpu': '2x 2.2GHz Kryo 570 Gold (Cortex-A77) + 6x 1.8GHz Kryo 570 Silver (Cortex-A55)',
            'gpu': 'Qualcomm Adreno 619',
            'os': 'Android 13 (API 33), One UI 5.1',
            'kernel': 'Linux 4.19.152-perf (SMP PREEMPT aarch64)',
            'total_crawled_repos': len(crawled_results),
            'successful_crawled_repos': ok_count
        },
        'specialized_sources': sources,
        'crawled_repositories': crawled_results
    }

    out_file = '/sdcard/zalorust/agent1_kernel_hardware_findings.json'
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(findings, f, indent=2, ensure_ascii=False)
    print(f"Saved complete crawl findings to {out_file}")

if __name__ == '__main__':
    main()
