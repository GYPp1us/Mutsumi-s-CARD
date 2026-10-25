"""只读保全 WebDAV 备份、对比历史，并生成导入为新副本的本地 ZIP。

密码只从交互式输入或 MUTSUMI_RECOVERY_DAV_PASSWORD 环境变量读取。
此工具没有上传、删除、修改索引或应用手机数据的功能。
"""
import argparse
import base64
import copy
import getpass
import hashlib
import json
import math
import os
import re
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

HASH = re.compile(r'^[0-9a-f]{64}$')
SNAPSHOT = re.compile(r'^[0-9]+-[A-Za-z0-9-]+$')
MAX_IMAGE = 16 * 1024 * 1024
MAX_JSON = 8 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 禁止凭据跟随重定向到其他主机或其他路径。

class ReadOnlyDav:
    def __init__(self, base, username, password):
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('必须使用不含凭据、查询参数和片段的 HTTPS 地址')
        self.base = base.rstrip('/') + '/'
        self._authorization = 'Basic ' + base64.b64encode((username+':'+password).encode()).decode()
        self._opener = urllib.request.build_opener(NoRedirect())

    def url(self, path):
        if path == '': return self.base
        decoded = urllib.parse.unquote(path)
        if not path or decoded.startswith('/') or '\\' in decoded or any(p in ('.','..') for p in decoded.split('/')):
            raise ValueError('远端路径越界')
        if urllib.parse.urlsplit(decoded).scheme or '//' in decoded or '?' in decoded or '#' in decoded:
            raise ValueError('只允许相对于已确认 WebDAV 根目录的路径')
        return self.base + urllib.parse.quote(decoded, safe='/-._')

    def request(self, method, path, limit=MAX_JSON):
        if method not in ('GET', 'PROPFIND'):
            raise ValueError('恢复工具只允许 GET 与 PROPFIND')
        headers = {'Authorization': self._authorization, 'User-Agent': 'MutsumiCard-ReadOnly-Recovery'}
        body = None
        if method == 'PROPFIND':
            headers.update({'Depth':'1', 'Content-Type':'application/xml; charset=utf-8'})
            body = b'<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>'
        request = urllib.request.Request(self.url(path), data=body, method=method, headers=headers)
        try:
            with self._opener.open(request, timeout=60) as response:
                data = response.read(limit+1)
                if len(data)>limit: raise ValueError('远端响应超过只读取证大小限制')
                return data
        except urllib.error.HTTPError as error:
            if error.code == 404 and method == 'GET': return None
            raise RuntimeError(f'只读 {method} 返回 HTTP {error.code}；未输出响应正文或凭据') from None

    def list(self, path):
        relative = path.rstrip('/')+'/' if path else ''
        data = self.request('PROPFIND', relative)
        prefix = urllib.parse.unquote(urllib.parse.urlsplit(self.url(relative)).path)
        origin = urllib.parse.urlsplit(self.base)
        rows = []
        for response in ET.fromstring(data).findall('{DAV:}response'):
            href = response.findtext('{DAV:}href')
            if not href: continue
            parsed = urllib.parse.urlsplit(urllib.parse.urljoin(self.base, href))
            decoded = urllib.parse.unquote(parsed.path)
            if (parsed.scheme,parsed.netloc)!=(origin.scheme,origin.netloc) or not decoded.startswith(prefix):
                continue
            name = decoded[len(prefix):].strip('/')
            if not name or '/' in name or '\\' in name or name in ('.','..'): continue
            rows.append({'name':name, 'directory':response.find('.//{DAV:}collection') is not None,
                         'size':response.findtext('.//{DAV:}getcontentlength'),
                         'modified':response.findtext('.//{DAV:}getlastmodified')})
        return rows

def normalize_document(value):
    value = copy.deepcopy(value)
    if value.get('formatVersion',1) not in (1,2): raise ValueError('不支持的快照格式')
    snapshot = value['snapshot']
    if snapshot['decks'] and 'syncId' not in snapshot['decks'][0]:
        image_hashes = {item['localPath']:item['sha256'] for item in value['images']}
        snapshot['decks'] = [dict(syncId=f"legacy-deck-{d['id']}", **{k:d[k] for k in ('name','createdAt','updatedAt')}) for d in snapshot['decks']]
        snapshot['cards'] = [dict(syncId=f"legacy-card-{c['id']}", deckSyncId=f"legacy-deck-{c['deckId']}",
                                  valueImageSha256=image_hashes[c['valueImagePath']],
                                  frontImageSha256=image_hashes[c['frontImagePath']] if c.get('frontImagePath') else None,
                                  **{k:c[k] for k in ('keyText','createdAt','updatedAt','archived')}) for c in snapshot['cards']]
        snapshot['reviews'] = [dict(cardSyncId=f"legacy-card-{r['cardId']}", **{k:v for k,v in r.items() if k!='cardId'}) for r in snapshot['reviews']]
    for collection,key in [('decks','syncId'),('cards','syncId'),('reviews','cardSyncId')]:
        identifiers = [item[key] for item in snapshot[collection]]
        if len(identifiers)!=len(set(identifiers)) or any(not isinstance(i,str) or not i for i in identifiers):
            raise ValueError('快照含重复或无效稳定 ID')
    refs = {}
    for reference in value['images']:
        digest,size = reference['sha256'],reference['size']
        if not HASH.fullmatch(digest) or not isinstance(size,int) or not 0<size<=MAX_IMAGE:
            raise ValueError('图片哈希或大小无效')
        if digest in refs and refs[digest]['size']!=size: raise ValueError('同一图片哈希有冲突大小')
        refs[digest] = {'sha256':digest,'size':size}
    deck_ids = {d['syncId'] for d in snapshot['decks']}
    card_ids = {c['syncId'] for c in snapshot['cards']}
    if {r['cardSyncId'] for r in snapshot['reviews']}!=card_ids: raise ValueError('卡片复习状态不完整')
    for record in snapshot['decks']+snapshot['cards']:
        if any(not isinstance(record[k],int) or record[k]<0 for k in ('createdAt','updatedAt')): raise ValueError('记录时间无效')
    for deck in snapshot['decks']:
        if not isinstance(deck['name'],str) or not deck['name'].strip(): raise ValueError('空卡组名')
    for card in snapshot['cards']:
        if card['deckSyncId'] not in deck_ids or not card['keyText'].strip(): raise ValueError('卡片或卡组关系无效')
        if any(digest not in refs for digest in [card['valueImageSha256'],card.get('frontImageSha256')] if digest):
            raise ValueError('快照缺少图片引用')
    for review in snapshot['reviews']:
        if not isinstance(review['weight'],(int,float)) or not math.isfinite(review['weight']) or not 0.2<=review['weight']<=5:
            raise ValueError('复习权重无效')
        counts = [review[k] for k in ('seenCount','againCount','unsureCount','knownCount')]
        if any(not isinstance(n,int) or n<0 for n in counts) or counts[0]!=sum(counts[1:]):
            raise ValueError('复习计数无效')
        if review['lastReviewedAt'] is not None and (not isinstance(review['lastReviewedAt'],int) or review['lastReviewedAt']<0):
            raise ValueError('复习时间无效')
    value['images'] = list(refs.values())
    return value

def analyze(index, documents):
    latest = index.get('snapshots',[{}])[0].get('id') if index.get('snapshots') else None
    if latest and latest not in documents: raise ValueError('索引引用的当前快照缺失')
    normalized = {key:normalize_document(value) for key,value in documents.items()}
    current = {c['syncId']:c for c in normalized[latest]['snapshot']['cards']} if latest else {}
    missing,changed = {},{}
    summaries = []
    indexed = {s['id']:s for s in index.get('snapshots',[])}
    for sid,document in normalized.items():
        snapshot = document['snapshot']
        summaries.append({'id':sid,'indexed':sid in indexed,'createdAt':indexed.get(sid,{}).get('createdAt'),
                          'deckCount':len(snapshot['decks']),'cardCount':len(snapshot['cards'])})
        for card in snapshot['cards']:
            entry = {**card,'snapshotId':sid}
            if card['syncId'] not in current: missing[(card['syncId'],sid)] = entry
            elif card!=current[card['syncId']]: changed[(card['syncId'],sid)] = entry
    return {'latest_snapshot':latest,'snapshots':summaries,'missing_from_latest':list(missing.values()),
            'historical_variants':list(changed.values()),'note':'当前版本按索引顺序确定，不根据设备时间戳选择胜出数据。历史候选不代表均为误删。'}

def json_bytes(value):
    return json.dumps(value,ensure_ascii=False,separators=(',',':')).encode('utf-8')

def make_recovery_zip(snapshot_id, document, objects, target):
    if not SNAPSHOT.fullmatch(snapshot_id): raise ValueError('快照 ID 无效')
    document = normalize_document(document)
    snapshot = document['snapshot']
    deck_ids = {d['syncId']:i+1 for i,d in enumerate(snapshot['decks'])}
    card_ids = {c['syncId']:i+1 for i,c in enumerate(snapshot['cards'])}
    database = {'decks':[], 'cards':[], 'reviews':[]}
    for deck in snapshot['decks']:
        database['decks'].append(dict(id=deck_ids[deck['syncId']],name=f"恢复副本 · {deck['name']} · {snapshot_id}",
                                      createdAt=deck['createdAt'],updatedAt=deck['updatedAt']))
    contents = {}
    sizes = {r['sha256']:r['size'] for r in document['images']}
    for card in snapshot['cards']:
        card_id = card_ids[card['syncId']]
        row = dict(id=card_id,deckId=deck_ids[card['deckSyncId']],**{k:card[k] for k in ('keyText','createdAt','updatedAt','archived')})
        for field,hash_field,face in [('valueImagePath','valueImageSha256','back'),('frontImagePath','frontImageSha256','front')]:
            digest = card.get(hash_field)
            row[field] = None
            if digest:
                path = f'images/recovered-{card_id}-{face}.png'
                data = (Path(objects)/f'{digest}.png').read_bytes()
                if len(data)!=sizes[digest] or hashlib.sha256(data).hexdigest()!=digest or not data.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('恢复图片缺失、损坏或不是 PNG')
                if len(data)<33 or data[12:16]!=b'IHDR' or struct.unpack('>II',data[16:24]) not in ((1024,1624),(1024,2048)):
                    raise ValueError('图片尺寸不符合原应用导入要求')
                contents[path],row[field] = data,path
        database['cards'].append(row)
    for review in snapshot['reviews']:
        database['reviews'].append(dict(cardId=card_ids[review['cardSyncId']],**{k:review[k] for k in
            ('weight','seenCount','againCount','unsureCount','knownCount','lastReviewedAt')}))
    contents['database.json'] = json_bytes(database)
    if len(contents['database.json'])>2*1024*1024: raise ValueError('恢复数据库超过原应用导入限制，请分批恢复')
    manifest = {'formatVersion':2,'exportedAt':int(time.time()*1000),'appVersion':'0.9.0',
                'deckCount':len(database['decks']),'cardCount':len(database['cards']),
                'reviewCount':len(database['reviews']),'imageCount':len(contents)-1,
                'resources':[{'path':p,'size':len(b),'sha256':hashlib.sha256(b).hexdigest()} for p,b in sorted(contents.items())]}
    contents['manifest.json'] = json_bytes(manifest)
    if sum(map(len,contents.values()))>MAX_ARCHIVE: raise ValueError('恢复包超过原应用 64 MiB 导入限制，请分批恢复')
    target = Path(target)
    if target.exists(): raise ValueError('拒绝覆盖已有恢复包')
    temporary = target.with_name(target.name+'.part')
    try:
        with temporary.open('xb') as output:
            with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
                for path,data in contents.items(): archive.writestr(path,data)
            output.flush(); os.fsync(output.fileno())
        if temporary.stat().st_size>MAX_ARCHIVE: raise ValueError('恢复 ZIP 超过原应用导入限制')
        temporary.replace(target)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise

def save_evidence(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as output:
        output.write(data); output.flush(); os.fsync(output.fileno())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True); parser.add_argument('--username',required=True)
    parser.add_argument('--remote-directory',default='MutsumiCard')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--discover',action='store_true')
    parser.add_argument('--download-images',action='store_true')
    parser.add_argument('--include-orphans',action='store_true')
    parser.add_argument('--restore-snapshot',action='append',default=[])
    args = parser.parse_args()
    password = os.environ.get('MUTSUMI_RECOVERY_DAV_PASSWORD') or getpass.getpass('WebDAV 密码（不会保存或回显）: ')
    client = ReadOnlyDav(args.url,args.username,password)
    args.output.mkdir(parents=True,exist_ok=False)
    if args.discover:
        listing = client.list('')
        save_evidence(args.output/'directory.json',json_bytes(listing))
        print(json.dumps(listing,ensure_ascii=False)); return
    root = args.remote_directory.strip('/')
    client.url(root+'/index.json')
    before = client.request('GET',root+'/index.json')
    if before is None: raise ValueError('该目录没有 index.json；请核对应用中的远端目录')
    save_evidence(args.output/'index.json',before)
    index = json.loads(before)
    ids = {s['id'] for s in index.get('snapshots',[])}
    warnings = []
    try:
        listing = client.list(root+'/snapshots')
        save_evidence(args.output/'snapshot-directory.json',json_bytes(listing))
        ids.update(r['name'][:-5] for r in listing if not r['directory'] and r['name'].endswith('.json') and SNAPSHOT.fullmatch(r['name'][:-5]))
    except RuntimeError as error:
        warnings.append(str(error)+'；仅保全索引可见快照，未穷尽孤立快照。')
    if len(ids)>10000: raise ValueError('快照数量超出取证限制')
    documents = {}
    for sid in sorted(ids):
        if not SNAPSHOT.fullmatch(sid): raise ValueError('快照 ID 无效')
        data = client.request('GET',root+f'/snapshots/{sid}.json')
        if data is None:
            warnings.append('快照缺失：'+sid); continue
        save_evidence(args.output/'snapshots'/f'{sid}.json',data)
        documents[sid] = normalize_document(json.loads(data))
    report = analyze(index,documents)
    hashes = {r['sha256'] for d in documents.values() for r in d['images']}
    try:
        listing = client.list(root+'/objects')
        save_evidence(args.output/'object-directory.json',json_bytes(listing))
        available = {r['name'][:-4] for r in listing if r['name'].endswith('.png') and HASH.fullmatch(r['name'][:-4])}
        report['orphan_image_hashes'] = sorted(available-hashes)
    except RuntimeError as error:
        warnings.append(str(error)+'；未能列举无引用图片。')
        report['orphan_image_hashes'] = []
    if args.download_images or args.restore_snapshot or args.include_orphans:
        wanted = hashes if args.download_images else {r['sha256'] for sid in args.restore_snapshot for r in documents[sid]['images']}
        if args.include_orphans: wanted |= set(report['orphan_image_hashes'])
        downloaded = 0
        for digest in sorted(wanted):
            data = client.request('GET',root+f'/objects/{digest}.png',MAX_IMAGE)
            if data is None:
                warnings.append('图片对象缺失：'+digest); continue
            if hashlib.sha256(data).hexdigest()!=digest: raise ValueError('云端图片哈希不匹配')
            downloaded += len(data)
            if downloaded>2*1024**3: raise ValueError('下载总量超过 2 GiB 限制；已有材料已保留')
            save_evidence(args.output/'objects'/f'{digest}.png',data)
        report['downloaded_bytes'] = downloaded
    for sid in args.restore_snapshot:
        make_recovery_zip(sid,documents[sid],args.output/'objects',args.output/f'恢复副本-{sid}.zip')
    after = client.request('GET',root+'/index.json')
    report['index_changed_during_audit'] = after!=before
    if after is not None and after!=before: save_evidence(args.output/'index-after.json',after)
    report['warnings'] = warnings
    save_evidence(args.output/'audit.json',json_bytes(report))
    print(json.dumps({'快照数':len(documents),'历史中当前缺失卡片候选数':len(report['missing_from_latest']),
                      '无引用图片数':len(report['orphan_image_hashes']),'警告数':len(warnings),
                      '取证期间索引变化':report['index_changed_during_audit'],'材料目录':str(args.output)},ensure_ascii=False))

if __name__=='__main__': main()
