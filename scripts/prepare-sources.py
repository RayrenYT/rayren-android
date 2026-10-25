"""Collect the exact locally-built sources and license notices; no credentials."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess

root=Path(__file__).resolve().parents[1]
stage=Path(os.environ['TEMP'])/'rayren-native-build'
source=root/'.research'/'veil'
upstreams=root/'native'/'upstreams'
upstreams.mkdir(parents=True,exist_ok=True)
inventory=[]
for name in ('AndroidLibXrayLite','OpenFlux','olcrtc','hev-socks5-tunnel'):
    repo=source/name
    sha=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
    if name=='hev-socks5-tunnel':
        repo=Path(os.environ['TEMP'])/'rayren-hev-build'/'jni'
    shutil.copytree(repo,upstreams/name,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.git','build','bin','__pycache__'))
    inventory.append({'name':name,'commit':sha})
shutil.copytree(stage/'bridge'/'vendor',root/'native'/'vendor',dirs_exist_ok=True)
(root/'docs'/'research'/'pinned-native-sources.json').write_text(json.dumps(inventory,indent=2)+'\n',encoding='utf-8')

licenses=root/'app'/'src'/'main'/'assets'/'licenses'
licenses.mkdir(parents=True,exist_ok=True)
shutil.copyfile(root/'LICENSE',licenses/'Rayren-GPL-3.0.txt')
index=['Rayren 0.1.0-alpha - GPLv3','Copyright 2026 Rayren contributors','',
       'Native integration is based on the public Veil build approach. Rayren UI, policy compiler, vault and controller are new.',
       'Exact source revisions: docs/research/pinned-native-sources.json in the source archive.',
       'OpenFlux: GPLv3; AndroidLibXrayLite: LGPLv3; Xray: MPL2; original olcRTC: WTFPLv2; HEV: MIT.',
       '', 'Included notices:']
for base,label in ((root/'native'/'vendor','Go'),(upstreams,'Native')):
    for file in sorted(base.rglob('*')):
        if file.is_file() and file.name.lower().startswith(('license','copying','notice','copyright')) and file.stat().st_size<300000:
            name=label+'_'+hashlib.sha256(str(file.relative_to(base)).encode()).hexdigest()[:12]+'.txt'
            shutil.copyfile(file,licenses/name)
            index.append(f'{name}: {file.relative_to(base).as_posix()}')
index+=['','Android/JVM libraries:',
 'Kotlin and kotlinx.coroutines - JetBrains, Apache-2.0',
 'AndroidX / Compose / WorkManager - Android Open Source Project, Apache-2.0',
 'Gson - Google, Apache-2.0',
 'OkHttp / Okio - Square, Apache-2.0',
 'ZXing - ZXing authors; ZXing Android Embedded - Journey Mobile, Apache-2.0',
 'Bouncy Castle - Legion of the Bouncy Castle, MIT-style license',
 'jspecify - JSpecify contributors, Apache-2.0',
 '', 'GeoIP / GeoSite: Loyalsoldier/v2ray-rules-dat release 202610152206, GPL-3.0 repository.',
 'GeoIP includes GeoLite2 data created by MaxMind (https://www.maxmind.com), CC BY-SA 4.0.',
 'GeoSite includes v2fly/domain-list-community (MIT), see publisher source links in THIRD_PARTY_NOTICES.md.',
 'For build instructions and corresponding source see the supplied Rayren source archive.']
(licenses/'INDEX.txt').write_text('\n'.join(index)+'\n',encoding='utf-8')
print(f'Prepared {len(inventory)} pinned upstreams and {len(list(licenses.iterdir()))} notice files')
