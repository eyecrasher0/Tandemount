#!/usr/bin/env python3
"""Compile the original universal plugin against a real upstream Bukkit API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from project_metadata import artifact_name, metadata

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('TANDEMOUNT_CACHE', ROOT / '.agents/cache'))
LOCAL_JAVAS = json.loads((ROOT / '.agents/toolchains.json').read_text()) if (ROOT / '.agents/toolchains.json').is_file() else {}
JAVA_HOME = os.environ.get('JAVA_HOME') or os.environ.get('TANDEMOUNT_JAVA_25') or LOCAL_JAVAS.get('25')


def main():
    project = metadata()
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', action='store_true', help='Build disposable native-test fixture variant')
    parser.add_argument('--api', type=Path, help='Real upstream Paper/Spigot API or patched server JAR')
    parser.add_argument('--classpath', action='append', type=Path, default=[],
                        help='Additional upstream API dependency JAR; may be repeated')
    parser.add_argument('--java-home', type=Path, default=Path(JAVA_HOME) if JAVA_HOME else None)
    parser.add_argument('--minecraft', help='Bind a release artifact to this exact Minecraft target')
    parser.add_argument('--all', action='store_true', help='Build all exact plugin Minecraft targets from platforms.json')
    parser.add_argument('--output', type=Path, default=ROOT / '.agents/plugin-build')
    args = parser.parse_args()
    api = args.api
    if api is None:
        candidates = [CACHE / 'plugin/cache/patched_1.12.2.jar',
                      CACHE / 'servers/paper/1.12.2/cache/patched_1.12.2.jar']
        api = next((p for p in candidates if p.is_file()), None)
    if api is None or not api.is_file():
        parser.error('Provide --api with a real upstream Bukkit/Paper API or patched server JAR (not a stub).')
    missing = [path for path in args.classpath if not path.is_file()]
    if missing:
        parser.error('Additional classpath JAR does not exist: ' + ', '.join(str(path) for path in missing))
    with zipfile.ZipFile(api) as jar:
        if 'org/bukkit/plugin/java/JavaPlugin.class' not in jar.namelist():
            parser.error('The supplied API does not contain JavaPlugin.')
    args.output.mkdir(parents=True, exist_ok=True)
    classes = args.output / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    sources = sorted((ROOT / 'plugin/src/main/java').rglob('*.java'))
    if args.fixture:
        sources += sorted((ROOT / 'plugin/src/test/java').rglob('*.java'))
    javac = str(args.java_home / 'bin/javac') if args.java_home else shutil.which('javac')
    if not javac: parser.error('Provide --java-home or JAVA_HOME with JDK 9 or newer.')
    compile_classpath = [api] + args.classpath
    command = [javac, '--release', '8', '-encoding', 'UTF-8',
               '-Xlint:all', '-classpath', os.pathsep.join(str(path.resolve()) for path in compile_classpath),
               '-d', str(classes), *map(str, sources)]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (args.output / 'compile.log').write_text(result.stdout)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.returncode)
    targets = [args.minecraft] if args.minecraft else [None]
    if args.all:
        targets = [t['minecraft'] for t in json.loads((ROOT / 'platforms.json').read_text())['targets']
                   if any(t['platforms'].get(platform, {}).get('available') for platform in ('paper', 'spigot', 'purpur', 'folia'))]
    def write(jar, name, data):
        entry = zipfile.ZipInfo(name, (2026, 11, 13, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o644 << 16
        jar.writestr(entry, data)
    artifacts = []
    for target in targets:
        name = artifact_name('bukkit', target) if target else f"{project['id']}-bukkit-{project['version']}.jar"
        artifact = args.output / name
        with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED) as jar:
            manifest = (f"Manifest-Version: 1.0\nImplementation-Title: {project['name']}\n"
                        f"Implementation-Version: {project['version']}\nImplementation-Vendor: {project['author']}\n"
                        "paperweight-mappings-namespace: mojang\nTandemount-Loader: bukkit\n")
            if target: manifest += 'Tandemount-Minecraft-Target: ' + target + '\n'
            write(jar, 'META-INF/MANIFEST.MF', manifest + '\n')
            for tree in [classes, ROOT / 'plugin/src/main/resources']:
                for file in sorted(tree.rglob('*')):
                    if file.is_file():
                        relative = file.relative_to(tree).as_posix()
                        if 'NativeFixture' not in relative:
                            data = (file.read_text().replace('${projectVersion}', project['version']).encode('utf-8')
                                    if relative == 'plugin.yml' else file.read_bytes())
                            write(jar, relative, data)
            write(jar, 'META-INF/LICENSE', (ROOT / 'LICENSE').read_bytes())
        artifacts.append({'minecraft': target or 'universal', 'artifact': str(artifact.resolve()), 'artifact_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest()})
    if args.fixture:
        fixture = args.output / 'tandemount-1.0.0-fixture.jar'
        with zipfile.ZipFile(fixture, 'w', zipfile.ZIP_DEFLATED) as jar:
            for file in sorted(classes.rglob('NativeFixture*.class')):
                write(jar, file.relative_to(classes).as_posix(), file.read_bytes())
            write(jar, 'plugin.yml', 'name: TandemountNativeFixture\nversion: 1.0.0\nmain: dev.eyecrasher.tandemount.NativeFixture\nauthor: EyeCrasher\nfolia-supported: true\napi-version: "1.13"\ndepend: [Tandemount]\ncommands:\n  tmfixture:\n    description: Disposable native test controller\n')
    record = {'schema': 1, 'minecraft': args.minecraft or ('multiple' if args.all else '1.12.2'),
              'minecraft_targets': [entry['minecraft'] for entry in artifacts],
              'platform': 'paper', 'kind': 'compile',
              'status': 'passed', 'api': str(api), 'api_sha256': hashlib.sha256(api.read_bytes()).hexdigest(),
              'compile_classpath': [{'path': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                                    for path in compile_classpath],
              'source_files': [{'path': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                               for path in sources],
              'fixture': args.fixture, 'java_release': 8, 'artifact': str(artifact),
              'artifact_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(), 'artifacts': artifacts,
              'command': command, 'log': str(args.output / 'compile.log'),
              'claims': ['Original plugin sources compile against a real upstream Bukkit API.'],
              'not_claimed': ['Runtime compatibility', 'Native behavior', 'Visual verification']}
    (args.output / 'compile.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record))


if __name__ == '__main__':
    main()
