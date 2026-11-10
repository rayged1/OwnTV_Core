#!/usr/bin/env python3
"""Publish Maven artifacts into OwnTV's public, tokenless Maven repository.

    PAGES_TOKEN=... python3 tools/publish_pages.py <staging-dir> [--keep ARTIFACT=N ...]

The repository is the `gh-pages` branch of ahXN00/OwnTV_Core, served by GitHub Pages at
https://ahxn00.github.io/OwnTV_Core/maven — no login needed to download. Both OwnTV_Core and
OwnTV_libmpv publish through this script; the two copies (tools/publish_pages.py in each repo) are
identical and must stay so.

<staging-dir> is a local Maven repository Gradle has just published into. Every version found there
is copied in; publishing a version that already exists fails (published versions are immutable).
Then: the oldest versions beyond --keep are removed, maven-metadata.xml (+ checksums) is regenerated
from the directories, and the branch is replaced by ONE commit, so the repository never grows beyond
what is currently served (GitHub Pages sites are limited to 1 GB). A concurrent publish from the other
repository is handled by retrying on a rejected --force-with-lease push.

Finally it waits until GitHub Pages actually serves the new POMs, so nothing downstream (a release,
a pin-bump pull request) runs ahead of the files.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

REPO = os.environ.get("PAGES_REPO", "ahXN00/OwnTV_Core")
BRANCH = "gh-pages"
SITE = "https://ahxn00.github.io/OwnTV_Core/maven"
GROUP_PATH = "tv/own/owntv"
GROUP = "tv.own.owntv"
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")


def git(*args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True)


def git_out(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def version_key(v):
    return [int(p) if p.isdigit() else p for p in v.replace("-", ".").split(".")]


def versions_in(artifact_dir):
    return sorted((d for d in os.listdir(artifact_dir) if os.path.isdir(os.path.join(artifact_dir, d))), key=version_key)


def staged(staging):
    """{artifact: [versions]} found in the Gradle staging repository."""
    base = os.path.join(staging, GROUP_PATH)
    return {a: versions_in(os.path.join(base, a)) for a in sorted(os.listdir(base))}


def write_metadata(artifact_dir, artifact):
    vs = versions_in(artifact_dir)
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n'
        f"  <groupId>{GROUP}</groupId>\n  <artifactId>{artifact}</artifactId>\n  <versioning>\n"
        f"    <latest>{vs[-1]}</latest>\n    <release>{vs[-1]}</release>\n    <versions>\n"
        + "".join(f"      <version>{v}</version>\n" for v in vs)
        + f"    </versions>\n    <lastUpdated>{time.strftime('%Y%m%d%H%M%S', time.gmtime())}</lastUpdated>\n"
        "  </versioning>\n</metadata>\n"
    ).encode()
    path = os.path.join(artifact_dir, "maven-metadata.xml")
    with open(path, "wb") as f:
        f.write(xml)
    for alg in CHECKSUMS:
        with open(f"{path}.{alg}", "w", newline="\n") as f:
            f.write(hashlib.new(alg, xml).hexdigest())


def attempt(staging, keep, token):
    work = tempfile.mkdtemp()
    url = f"https://x-access-token:{token}@github.com/{REPO}.git"
    git("clone", "-q", "--depth", "1", "--branch", BRANCH, url, work, cwd=".")
    git("config", "core.autocrlf", "false", cwd=work)
    base_sha = git_out("rev-parse", "HEAD", cwd=work)

    new = staged(staging)
    for artifact, versions in new.items():
        dest_artifact = os.path.join(work, "maven", GROUP_PATH, artifact)
        for v in versions:
            dest = os.path.join(dest_artifact, v)
            if os.path.exists(dest):
                sys.exit(f"{GROUP}:{artifact}:{v} is already published — published versions are immutable; "
                         "release the next version instead.")
            shutil.copytree(os.path.join(staging, GROUP_PATH, artifact, v), dest)
        present = versions_in(dest_artifact)
        limit = keep.get(artifact)
        if limit and len(present) > limit:
            for old in present[:-limit]:
                print(f"pruning {artifact} {old} (keeping the newest {limit})")
                shutil.rmtree(os.path.join(dest_artifact, old))
        write_metadata(dest_artifact, artifact)

    summary = ", ".join(f"{a} {' '.join(vs)}" for a, vs in new.items())
    git("checkout", "-q", "--orphan", "publish", cwd=work)
    git("add", "-A", cwd=work)
    git("-c", "user.name=OwnTV", "-c", "user.email=xiannero@gmail.com",
        "commit", "-q", "-m", f"Maven repository: {summary}", cwd=work)
    push = subprocess.run(
        ["git", "push", "-q", f"--force-with-lease={BRANCH}:{base_sha}", "origin", f"publish:{BRANCH}"], cwd=work)
    shutil.rmtree(work, ignore_errors=True)
    return push.returncode == 0, new


def wait_served(new, timeout=900):
    pending = [f"{SITE}/{GROUP_PATH}/{a}/{v}/{a}-{v}.pom" for a, vs in new.items() for v in vs]
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        still = []
        for u in pending:
            try:
                with urllib.request.urlopen(urllib.request.Request(u, method="HEAD"), timeout=30) as r:
                    if r.status != 200:
                        still.append(u)
            except Exception:
                still.append(u)
        pending = still
        if pending:
            time.sleep(20)
    if pending:
        sys.exit(f"GitHub Pages did not serve these within {timeout}s: {pending}")
    print("served by GitHub Pages:", ", ".join(f"{a} {' '.join(vs)}" for a, vs in new.items()))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("staging")
    p.add_argument("--keep", action="append", default=[], help="ARTIFACT=N: keep the newest N versions")
    args = p.parse_args()
    keep = {k: int(v) for k, v in (x.split("=") for x in args.keep)}
    token = os.environ.get("PAGES_TOKEN")
    if not token:
        sys.exit("PAGES_TOKEN is not set.")
    for n in range(1, 6):
        ok, new = attempt(args.staging, keep, token)
        if ok:
            wait_served(new)
            return
        print(f"push rejected (another publish landed first) — retry {n}/5")
        time.sleep(10 * n)
    sys.exit("could not publish after 5 attempts")


if __name__ == "__main__":
    main()
