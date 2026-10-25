"""Package the verified local alpha APK and matching corresponding source."""
from pathlib import Path
import hashlib, json, shutil, struct, zipfile, re
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
out=root/'artifacts'
out.mkdir(exist_ok=True)
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
report=[]

total=0
for folder in ('domain/build/test-results/test', 'app/build/test-results/testDebugUnitTest'):
    paths=list((root/folder).glob('TEST-*.xml'))
    assert paths, f'Missing test results: {folder}'
    for path in paths:
        suite=ET.parse(path).getroot()
        assert int(suite.attrib['failures'])==0 and int(suite.attrib['errors'])==0, path
        assert int(suite.attrib.get('skipped',0))==0, path
        total+=int(suite.attrib['tests'])
assert total>=157
report.append(f'JVM tests: {total}, zero failures/errors')
with zipfile.ZipFile(apk) as archive:
    libs=[name for name in archive.namelist() if name.endswith('.so')]
    assert libs and all('/arm64-v8a/' in name for name in libs)
    assert len([name for name in archive.namelist() if name.startswith('assets/licenses/')])>=100
    for name in libs:
        data=archive.read(name)
        assert data[:4]==b'\x7fELF' and data[4]==2 and data[5]==1,name
        phoff=struct.unpack_from('<Q',data,32)[0]
        entsize,count=struct.unpack_from('<HH',data,54)
        aligns=[struct.unpack_from('<Q',data,phoff+i*entsize+48)[0] for i in range(count) if struct.unpack_from('<I',data,phoff+i*entsize)[0]==1]
        assert aligns and min(aligns)>=16384,(name,aligns)
        report.append(f'{name}: ELF PT_LOAD alignment {min(aligns)} bytes')
    with zipfile.ZipFile(root/'app/libs/rayrencore.aar') as native:
        assert archive.read('lib/arm64-v8a/libgojni.so')==native.read('jni/arm64-v8a/libgojni.so')
    report.append('APK libgojni.so matches bundled rayrencore.aar exactly')
    for name in ('geoip.dat','geosite.dat'):
        data=archive.read('assets/'+name)
        assert data==(root/'app/src/main/assets'/name).read_bytes()
        report.append(f'{name}: packaged bytes match verified publisher download')
version=re.search(r'versionName\s*=\s*"([0-9A-Za-z.-]+)"',(root/'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
metadata=json.loads((apk.parent/'output-metadata.json').read_text(encoding='utf-8'))
assert metadata['elements'][0]['versionName']==version, 'Stale APK version'
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.attrib.get('severity') in ('Error','Fatal') for i in lint.findall('issue')),'Android lint errors'
report.append('Android lint: no errors; physical-device validation pending')
destination=out/f'Rayren-{version}-arm64-v8a.apk'
shutil.copyfile(apk,destination)
shutil.copyfile(root/'README.md',out/'START_HERE.md')
shutil.copyfile(root/'docs/DEVICE_TEST_CHECKLIST.md',out/'PHONE_TEST.md')
(out/'verification.txt').write_text('\n'.join(report)+'\n',encoding='utf-8')

# Convert go-list's JSON stream into a portable module inventory.
modulefile=root/'docs/research/native-modules.json'
raw=modulefile.read_text(encoding='utf-8-sig').strip()
if not raw.startswith('['):
    decoder=json.JSONDecoder();modules=[]
    while raw:
        item,end=decoder.raw_decode(raw);raw=raw[end:].lstrip()
        for key in ('Dir','GoMod'):item.pop(key,None)
        if 'Replace' in item:
            for key in ('Dir','GoMod'):item['Replace'].pop(key,None)
        modules.append(item)
    modulefile.write_text(json.dumps(modules,indent=2)+'\n',encoding='utf-8')

source=out/f'Rayren-{version}-source.zip'
excluded={'.git','.gradle','.kotlin','.research','build','artifacts','.idea','__pycache__'}
def include(path):
    rel=path.relative_to(root)
    return not any(part in excluded for part in rel.parts) and path.name!='local.properties' and path.suffix not in ('.log','.jks','.keystore','.pyc')
with zipfile.ZipFile(source,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=5) as archive:
    for path in sorted(root.rglob('*')):
        if path.is_file() and include(path):
            archive.write(path,'rayren-android/'+path.relative_to(root).as_posix())
    assert 'rayren-android/native/vendor/modules.txt' in archive.namelist()
    assert 'rayren-android/native/upstreams/OpenFlux/LICENSE' in archive.namelist()
checksums=[]
for path in (destination,source):
    checksum=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
    checksums.append(f'{checksum}  {path.name}')
    print(f'{path.name}: {path.stat().st_size} bytes; SHA256 {checksum}',flush=True)
(out/'SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n',encoding='utf-8')
print('\n'.join(report),flush=True)
