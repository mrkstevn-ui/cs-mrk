#!/usr/bin/env python3
"""Verify configured HTTPS domains; apply source updates only with --apply."""
import argparse
import ipaddress
import json
import re
import ssl
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = 'Mozilla/5.0 CloudstreamDomainCheck/1.0'
SSL_CTX = ssl.create_default_context()
MAIN_URL = re.compile(r'(override\s+var\s+mainUrl\s*=\s*)"[^"]+"')
MAX_BODY = 2 * 1024 * 1024


def normalize_origin(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ''
    if (parsed.scheme != 'https' or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443) or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment or not re.fullmatch(r'[a-zA-Z0-9.-]+', host)
            or '.' not in host or host.endswith('.') or any(not part or part.startswith('-') or part.endswith('-') for part in host.split('.'))):
        raise ValueError(f'Invalid HTTPS origin: {url}')
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ValueError('IP addresses are not provider domains')
    if host.endswith(('.localhost', '.local', '.internal')):
        raise ValueError('Local domains are not allowed')
    return 'https://' + host.lower()


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, allowed_hosts):
        super().__init__()
        self.allowed_hosts = allowed_hosts

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        origin = normalize_origin(urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, '', '', '')))
        if urllib.parse.urlsplit(origin).hostname not in self.allowed_hosts:
            raise ValueError(f'Unapproved redirect domain: {origin}; add it to mirrors after review')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def test_url(url, keyword, timeout=8, allowed_hosts=None):
    """Verify TLS, redirect destination and a non-challenge HTML page."""
    try:
        origin = normalize_origin(url)
        hosts = allowed_hosts or {urllib.parse.urlsplit(origin).hostname}
        opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=SSL_CTX), SafeRedirectHandler(hosts))
        req = urllib.request.Request(origin, headers={'User-Agent': USER_AGENT, 'Accept': 'text/html'})
        with opener.open(req, timeout=timeout) as resp:
            if resp.status != 200 or 'text/html' not in resp.headers.get('Content-Type', '').lower():
                return False, None
            raw = resp.read(MAX_BODY + 1)
            if len(raw) > MAX_BODY:
                return False, None
            body = raw.decode('utf-8', errors='replace').lower()
            if not keyword.strip() or any(marker in body for marker in (
                    'cf-chl-', 'just a moment', 'verify you are human', 'domain is for sale', 'sedoparking')):
                return False, None
            title = re.search(r'<title\b[^>]*>(.*?)</title>', body, re.S)
            # A mention in an advertisement or a link is not site identity.
            if not title or keyword.casefold() not in title.group(1).casefold():
                return False, None
            final = urllib.parse.urlsplit(resp.url)
            if final.hostname not in hosts:
                return False, None
            return True, normalize_origin(urllib.parse.urlunsplit((final.scheme, final.netloc, '', '', '')))
    except Exception as exc:
        print(f'  [CHECK FAILED] {url}: {type(exc).__name__}: {exc}', flush=True)
        return False, None


def candidates(cfg):
    urls = [cfg['current_url'], *cfg.get('mirrors', [])]
    if cfg.get('strategy') == 'numeric_increment':
        pattern = cfg.get('pattern', '')
        if pattern.count('{num}') != 1:
            raise ValueError('numeric_increment requires one {num} placeholder')
        match = re.fullmatch(re.escape(pattern).replace(r'\{num\}', r'(\d+)'), cfg['current_url'])
        if not match:
            raise ValueError('current_url does not match numeric pattern')
        urls += [pattern.format(num=int(match[1]) + offset) for offset in range(1, 11)]
    return list(dict.fromkeys(normalize_origin(url) for url in urls))


def update_kotlin_file(file_path, new_url, api_url=None):
    new_url = normalize_origin(new_url)
    path = Path(file_path)
    content = path.read_text(encoding='utf-8')
    if len(MAIN_URL.findall(content)) != 1:
        raise ValueError(f'{path}: expected exactly one mainUrl declaration')
    updated = MAIN_URL.sub(lambda m: m[1] + json.dumps(new_url), content)
    if api_url is not None:
        api_url = normalize_origin(api_url)
        api_pattern = re.compile(r'(override\s+var\s+apiUrl\s*=\s*)"[^"]+"')
        if len(api_pattern.findall(content)) != 1:
            raise ValueError(f'{path}: expected exactly one apiUrl declaration')
        updated = api_pattern.sub(lambda m: m[1] + json.dumps(api_url), updated)
    if updated == content:
        return False
    path.write_text(updated, encoding='utf-8')
    return True


def bump_gradle_version(gradle_path):
    path = Path(gradle_path)
    content = path.read_text(encoding='utf-8')
    pattern = re.compile(r'(?m)^version\s*=\s*(\d+)\s*$')
    matches = list(pattern.finditer(content))
    if len(matches) != 1:
        raise ValueError(f'{path}: expected exactly one plugin version')
    version = int(matches[0][1]) + 1
    path.write_text(pattern.sub(f'version = {version}\n', content), encoding='utf-8')
    return version


def run(root, apply=False):
    config_path = root / 'domains.json'
    config = json.loads(config_path.read_text(encoding='utf-8'))
    # Validate every source before allowing any mutations.
    for name, cfg in config.items():
        candidates(cfg)
        for key in ('file', 'gradle'):
            path = (root / cfg[key]).resolve()
            if not path.is_relative_to(root.resolve()) or not path.is_file():
                raise ValueError(f'{name}: invalid {key} path')
        if len(MAIN_URL.findall((root / cfg['file']).read_text(encoding='utf-8'))) != 1:
            raise ValueError(f'{name}: missing or ambiguous mainUrl')
        if cfg.get('api_url'):
            normalize_origin(cfg['api_url'])
            source = (root / cfg['file']).read_text(encoding='utf-8')
            if len(re.findall(r'override\s+var\s+apiUrl\s*=\s*"[^"]+"', source)) != 1:
                raise ValueError(f'{name}: missing or ambiguous apiUrl')
        gradle_source = (root / cfg['gradle']).read_text(encoding='utf-8')
        if len(re.findall(r'(?m)^version\s*=\s*\d+\s*$', gradle_source)) != 1:
            raise ValueError(f'{name}: missing or ambiguous plugin version')
        if not cfg.get('verification_keyword', '').strip():
            raise ValueError(f'{name}: missing verification keyword')
    changed, failed, versions = [], [], set()
    for name, cfg in config.items():
        print(f'[{name}] Checking {cfg["current_url"]}', flush=True)
        urls = candidates(cfg)
        hosts = {urllib.parse.urlsplit(url).hostname for url in urls}
        active = None
        for url in urls:
            ok, verified = test_url(url, cfg['verification_keyword'], allowed_hosts=hosts)
            if ok:
                active = verified
                break
        if active is None:
            failed.append(name)
            print('  [UNVERIFIED] No verified domain; configuration preserved.', flush=True)
            continue
        source = (root / cfg['file']).read_text(encoding='utf-8')
        source_url = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', source)[1]
        source_api = re.search(r'override\s+var\s+apiUrl\s*=\s*"([^"]+)"', source)
        api_changed = cfg.get('api_url') is not None and source_api is not None and source_api[1] != normalize_origin(cfg['api_url'])
        if active != cfg['current_url'] or active != source_url or api_changed:
            changed.append(name)
            print(f'  [UPDATE] {source_url} -> {active}' + ('' if apply else ' (dry run)'), flush=True)
            if apply:
                if update_kotlin_file(root / cfg['file'], active, cfg.get('api_url')):
                    versions.add(cfg['gradle'])
                cfg['current_url'] = active
        else:
            print('  [OK] HTTPS domain and source agree.', flush=True)
    if apply and changed:
        for gradle in sorted(versions):
            bump_gradle_version(root / gradle)
        config_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f'Summary: {len(config) - len(failed)} verified, {len(failed)} unverified, {len(changed)} updates.')
    if failed:
        print('Unverified: ' + ', '.join(failed))
    return bool(failed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Write verified changes (default: read-only)')
    args = parser.parse_args()
    return run(Path(__file__).resolve().parents[1], args.apply)


if __name__ == '__main__':
    raise SystemExit(main())
