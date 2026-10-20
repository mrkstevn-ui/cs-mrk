#!/usr/bin/env python3
"""
Automated Provider Domain Updater Bot
Checks streaming provider domains for redirects, updates Kotlin sources and increments plugin versions.
"""

import os
import re
import ssl
import json
import urllib.request
import urllib.parse
from typing import Optional, Tuple

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

def test_url(url: str, keyword: str, timeout: int = 8) -> Tuple[bool, Optional[str]]:
    """Tests a URL, follows redirects and verifies the page content."""
    try:
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            }
        )
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
            final_url = resp.url.rstrip("/")
            body = resp.read().decode("utf-8", errors="replace").lower()
            
            # Check if keyword is in page title or content
            if keyword.lower() in body:
                parsed = urllib.parse.urlparse(final_url)
                normalized_root = f"{parsed.scheme}://{parsed.netloc}".rstrip("/")
                return True, normalized_root
            return False, None
    except Exception as e:
        return False, None

def check_number_increment(base_url: str, keyword: str) -> Optional[str]:
    """Tests domain number increments like dizipal1591 -> dizipal1592."""
    match = re.search(r"(\d+)", base_url)
    if not match:
        return None
    num_str = match.group(1)
    base_num = int(num_str)
    
    # Try next 10 numbers
    for offset in range(1, 11):
        test_num = base_num + offset
        candidate = base_url.replace(num_str, str(test_num), 1)
        ok, verified = test_url(candidate, keyword)
        if ok and verified:
            return verified
    return None

def update_kotlin_file(file_path: str, new_url: str) -> bool:
    if not os.path.exists(file_path):
        return False
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    new_content = re.sub(
        r'override\s+var\s+mainUrl\s*=\s*"[^"]+"',
        f'override var mainUrl = "{new_url}"',
        content
    )
    if new_content != content:
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        return True
    return False

def bump_gradle_version(gradle_path: str) -> Optional[int]:
    if not os.path.exists(gradle_path):
        return None
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    match = re.search(r'version\s*=\s*(\d+)', content)
    if not match:
        return None
    cur_ver = int(match.group(1))
    new_ver = cur_ver + 1
    new_content = re.sub(
        r'version\s*=\s*\d+',
        f'version = {new_ver}',
        content
    )
    with open(gradle_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return new_ver

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    config_path = os.path.join(root_dir, "domains.json")

    if not os.path.exists(config_path):
        print(f"Error: {config_path} not found.")
        return

    with open(config_path, "r", encoding="utf-8") as f:
        domains_config = json.load(f)

    updated_providers = []

    print("========================================")
    print("   Checking Cloudstream TR Providers    ")
    print("========================================\n")

    for name, cfg in domains_config.items():
        cur_url = cfg.get("current_url")
        keyword = cfg.get("verification_keyword", "")
        file_path = os.path.join(root_dir, cfg.get("file", ""))
        gradle_path = os.path.join(root_dir, cfg.get("gradle", ""))
        mirrors = cfg.get("mirrors", [])

        print(f"[{name}] Checking {cur_url}...")

        is_alive, verified_url = test_url(cur_url, keyword)
        active_url = verified_url if (is_alive and verified_url) else None

        # If current URL failed or redirected, check mirrors / increments
        if not active_url:
            print(f"  [!] Current URL down or changed. Checking alternatives...")
            
            # Check number increment if applicable
            increment_candidate = check_number_increment(cur_url, keyword)
            if increment_candidate:
                active_url = increment_candidate
                print(f"  [+] Found working increment domain: {active_url}")
            
            # Check mirrors
            if not active_url:
                for mirror in mirrors:
                    if mirror == cur_url:
                        continue
                    m_ok, m_verified = test_url(mirror, keyword)
                    if m_ok and m_verified:
                        active_url = m_verified
                        print(f"  [+] Found working mirror: {active_url}")
                        break

        if active_url:
            # Check if domain actually changed
            if active_url != cur_url:
                print(f"  [*] DOMAIN UPDATE DETECTED: {cur_url} -> {active_url}")
                kt_updated = update_kotlin_file(file_path, active_url)
                new_ver = bump_gradle_version(gradle_path)
                cfg["current_url"] = active_url
                updated_providers.append({
                    "provider": name,
                    "old_url": cur_url,
                    "new_url": active_url,
                    "new_version": new_ver
                })
            else:
                print(f"  [OK] Domain is active and up to date.")
        else:
            print(f"  [WARN] Could not find any active domain for {name}!")

    if updated_providers:
        # Save updated domains.json
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(domains_config, f, indent=2, ensure_ascii=False)

        print("\n========================================")
        print("SUMMARY OF UPDATES:")
        for up in updated_providers:
            print(f" - {up['provider']}: {up['old_url']} -> {up['new_url']} (Version: {up['new_version']})")
        print("========================================")
    else:
        print("\nAll provider domains are healthy and current. No changes needed.")

if __name__ == "__main__":
    main()
