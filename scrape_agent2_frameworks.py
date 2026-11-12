import os
import sys
import json
import base64
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

def fetch_url(url, timeout=12):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Android AOSP Scraper)'})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()

def get_gitiles_json(url):
    raw = fetch_url(url)
    content = raw.decode('utf-8', errors='ignore')
    if content.startswith(")]}'"):
        content = content[4:]
    return json.loads(content)

def get_gitiles_text(url):
    raw = fetch_url(url)
    return base64.b64decode(raw).decode('utf-8', errors='ignore')

def main():
    with open('/sdcard/zalorust/repos.json') as f:
        all_repos = json.load(f)

    repo_names = {r['name']: r for r in all_repos}

    core_priority = [
        'platform/frameworks/base',
        'platform/frameworks/native',
        'platform/art',
        'platform/bionic',
        'platform/libcore',
        'platform/frameworks/av',
        'platform/frameworks/opt/gamesdk',
        'platform/frameworks/opt/gamedevicets',
        'platform/frameworks/hardware/interfaces',
        'platform/frameworks/minikin',
        'platform/frameworks/libs/systemui',
        'platform/frameworks/compile/libbcc',
        'platform/frameworks/compile/slang',
        'platform/frameworks/opt/bitmap',
        'platform/frameworks/opt/bluetooth',
        'platform/frameworks/opt/telephony',
        'platform/frameworks/opt/net/wifi',
        'platform/frameworks/opt/widget',
        'platform/frameworks/layoutlib',
        'platform/frameworks/libs/modules-utils',
        'platform/frameworks/support',
        'platform/frameworks/proto_logging',
        'platform/packages/modules/ANGLE',
        'platform/packages/modules/Runtime',
        'platform/packages/modules/Scheduling',
        'platform/packages/modules/StatsD',
        'platform/packages/modules/Virtualization',
        'platform/packages/modules/NeuralNetworks',
        'platform/packages/modules/Permission',
        'platform/packages/modules/adb',
        'platform/packages/modules/AdServices',
        'platform/packages/modules/ArtPrebuilt',
        'platform/packages/modules/AppSearch',
        'platform/packages/modules/Bluetooth',
        'platform/packages/modules/CellBroadcastService',
        'platform/packages/modules/ConfigInfrastructure',
        'platform/packages/modules/Connectivity',
        'platform/packages/modules/DeviceLock',
        'platform/packages/modules/DnsResolver',
        'platform/packages/modules/ExtServices',
        'platform/packages/modules/GeoServices',
        'platform/packages/modules/Gki',
        'platform/packages/modules/HealthFitness',
        'platform/packages/modules/ImsMedia',
        'platform/packages/modules/IPsec',
        'platform/packages/modules/Media',
        'platform/packages/modules/MediaProvider',
        'platform/packages/modules/NetworkPermissionConfig',
        'platform/packages/modules/NetworkStack',
        'platform/packages/modules/OnDevicePersonalization',
        'platform/packages/modules/RemoteKeyProvisioning',
        'platform/packages/modules/SdkExtensions',
        'platform/packages/modules/Telephony',
        'platform/packages/modules/Tethering',
        'platform/packages/modules/Uwb',
        'platform/packages/modules/Virtualization',
        'platform/packages/modules/Wifi',
        'platform/packages/apps/Settings',
        'platform/packages/apps/SettingsIntelligence',
        'platform/packages/services/AlternativeNetworkAccess',
        'platform/packages/services/BuiltInPrintService',
        'platform/packages/services/Car',
        'platform/packages/services/Mms',
        'platform/packages/services/Telecomm',
        'platform/packages/services/Telephony',
        'platform/system/core',
        'platform/system/logging',
        'platform/system/bpf',
        'platform/system/server_configurable_flags',
        'platform/system/memory/libmeminfo',
        'platform/system/memory/libmemunreachable',
        'platform/system/memory/libion',
        'platform/system/memory/lmkd',
        'platform/system/libhidl',
        'platform/system/libhwbinder',
        'platform/system/media',
        'platform/system/libprocinfo',
        'platform/system/libziparchive',
        'platform/system/sepolicy',
        'platform/system/tools/aidl',
        'platform/system/tools/hidl',
        'platform/system/apex',
        'platform/system/ashmemd',
        'platform/system/chre',
        'platform/system/gatekeeper',
        'platform/system/gsid',
        'platform/system/hwservicemanager',
        'platform/system/incremental_delivery',
        'platform/system/iorap',
        'platform/system/keymaster',
        'platform/system/librustutils',
        'platform/system/linkerconfig',
        'platform/system/netd',
        'platform/system/nfc',
        'platform/system/nvram',
        'platform/system/security',
        'platform/system/timezone',
        'platform/system/tools/sysprop',
        'platform/system/tools/xsdc',
        'platform/system/update_engine',
        'platform/system/vold',
        'platform/system/unwinding'
    ]

    selected = []
    for p in core_priority:
        if p in repo_names and p not in selected:
            selected.append(p)

    for r in all_repos:
        name = r['name']
        if len(selected) >= 112:
            break
        if name not in selected:
            if name.startswith('platform/frameworks/') or \
               name.startswith('platform/packages/modules/') or \
               name.startswith('platform/packages/services/') or \
               name.startswith('platform/system/'):
                selected.append(name)

    print(f"Selected {len(selected)} repos for deep scraping.")

    results = []

    def scrape_repo(repo_name):
        res = {
            "name": repo_name,
            "url": f"https://android.googlesource.com/{repo_name}",
            "status": "pending",
            "latest_commit": None,
            "commit_count": 0,
            "recent_messages": []
        }
        log_url = f"https://android.googlesource.com/{repo_name}/+log/HEAD?format=JSON&n=3"
        try:
            data = get_gitiles_json(log_url)
            log = data.get('log', [])
            res["status"] = "ok"
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
        except urllib.error.HTTPError as e:
            res["status"] = f"HTTP_{e.code}"
        except Exception as e:
            res["status"] = f"error: {str(e)[:60]}"
        return res

    with ThreadPoolExecutor(max_workers=12) as executor:
        futures = {executor.submit(scrape_repo, name): name for name in selected}
        count = 0
        for fut in as_completed(futures):
            res = fut.result()
            results.append(res)
            count += 1
            if count % 20 == 0 or count == len(selected):
                print(f"Scraped {count}/{len(selected)} repos...")

    # Categorize results
    categorized = {
        "frameworks_base_native": [],
        "art_runtime_bionic_libcore": [],
        "packages_modules_scheduling": [],
        "packages_apps_services": [],
        "system_core_memory_logging": []
    }

    for r in results:
        name = r["name"]
        if name.startswith("platform/frameworks/"):
            categorized["frameworks_base_native"].append(r)
        elif any(name.startswith(p) for p in ["platform/art", "platform/bionic", "platform/libcore"]):
            categorized["art_runtime_bionic_libcore"].append(r)
        elif name.startswith("platform/packages/modules/"):
            categorized["packages_modules_scheduling"].append(r)
        elif any(name.startswith(p) for p in ["platform/packages/apps/", "platform/packages/services/"]):
            categorized["packages_apps_services"].append(r)
        else:
            categorized["system_core_memory_logging"].append(r)

    print("Scraping completed. Summary:")
    for cat, items in categorized.items():
        ok_count = sum(1 for x in items if x["status"] == "ok")
        print(f"  {cat}: {len(items)} repos (OK: {ok_count})")

    with open('/sdcard/zalorust/repos_scraped_summary.json', 'w') as out:
        json.dump(results, out, indent=2)

if __name__ == '__main__':
    main()
