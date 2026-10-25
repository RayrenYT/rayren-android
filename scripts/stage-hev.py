"""Stage the pinned HEV sources, expanding Windows git symlink placeholders."""
from pathlib import Path
import shutil
import subprocess
import sys

source, target = (Path(p).resolve() for p in sys.argv[1:3])
shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('.git'))
for relative in ('', 'src/core', 'third-part/lwip', 'third-part/yaml', 'third-part/hev-task-system'):
    repo = source / relative
    if not (repo / '.git').exists():
        # The supplied corresponding-source archive contains already-expanded files.
        continue
    entries = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '-s'], text=True)
    for entry in entries.splitlines():
        metadata, name = entry.split('\t', 1)
        if not metadata.startswith('120000 '):
            continue
        link = target / relative / name
        resolved = (link.parent / link.read_text().strip()).resolve()
        if not resolved.is_relative_to(target) or not resolved.is_file():
            raise RuntimeError(f'Invalid HEV link: {relative}/{name}')
        shutil.copyfile(resolved, link)
print('HEV source staged with symlinks expanded')
