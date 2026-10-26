"""恢复工具仅使用合成数据，禁止接触真实 WebDAV。"""
import hashlib
import json
import tempfile
import struct
import unittest
import zipfile
import zlib
from pathlib import Path

from cloud_backup_recovery import ReadOnlyDav, analyze, normalize_document, make_recovery_zip

def valid_png():
    def chunk(kind,data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
    header=struct.pack('>IIBBBBB',1024,1624,8,2,0,0,0)
    pixels=(b'\x00'+b'\xff'*(1024*3))*1624
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')

PNG = valid_png()
HASH = hashlib.sha256(PNG).hexdigest()

def document(keys=('现有卡',)):
    return {'formatVersion': 2, 'snapshot': {
        'decks': [{'syncId': 'deck', 'name': '原卡组', 'createdAt': 1, 'updatedAt': 1}],
        'cards': [{'syncId': f'card-{i}', 'deckSyncId': 'deck', 'keyText': key,
                   'createdAt': 1, 'updatedAt': 1, 'archived': False,
                   'valueImageSha256': HASH, 'frontImageSha256': HASH} for i,key in enumerate(keys)],
        'reviews': [{'cardSyncId': f'card-{i}', 'weight': 1.0, 'seenCount': 0, 'againCount': 0,
                     'unsureCount': 0, 'knownCount': 0, 'lastReviewedAt': None} for i in range(len(keys))]},
        'images': [{'sha256': HASH, 'size': len(PNG)}]}

class RecoveryTest(unittest.TestCase):
    def test_仅允许只读请求且不把凭据放进地址(self):
        client = ReadOnlyDav('https://example.test/dav/publish', 'test', 'private')
        with self.assertRaises(ValueError): client.request('PUT', 'index.json')
        for url in ['http://example.test/dav', 'https://test:private@example.test/dav']:
            with self.assertRaises(ValueError): ReadOnlyDav(url, 'test', 'private')

    def test_拒绝跨域重定向及目录逃逸(self):
        client = ReadOnlyDav('https://example.test/dav/publish', 'test', 'private')
        for path in ['../secret', '/other', 'https://evil.test/x', 'snapshots/../../secret', 'objects/%2e%2e/secret']:
            with self.assertRaises(ValueError): client.url(path)

    def test_分析按索引顺序识别当前版本而非最大时间戳(self):
        index = {'snapshots': [{'id': '100-now', 'createdAt': 100}, {'id': '999-history', 'createdAt': 999}]}
        report = analyze(index, {'100-now': document(), '999-history': document(('现有卡', '丢失卡'))})
        self.assertEqual(report['latest_snapshot'], '100-now')
        self.assertEqual(report['missing_from_latest'][0]['syncId'], 'card-1')
        self.assertEqual(report['missing_from_latest'][0]['keyText'], '丢失卡')

    def test_重复稳定标识不能静默覆盖(self):
        value = document()
        value['snapshot']['cards'] *= 2
        with self.assertRaises(ValueError): normalize_document(value)

    def test_旧快照格式可转换且保留双面(self):
        value = {'formatVersion': 1, 'snapshot': {
            'decks': [{'id': 3, 'name': '旧组', 'createdAt': 1, 'updatedAt': 1}],
            'cards': [{'id': 8, 'deckId': 3, 'keyText': '旧卡', 'valueImagePath': 'images/back.png',
                       'frontImagePath': 'images/front.png', 'createdAt': 1, 'updatedAt': 1, 'archived': False}],
            'reviews': [{'cardId': 8, 'weight': 1.0, 'seenCount': 0, 'againCount': 0,
                         'unsureCount': 0, 'knownCount': 0, 'lastReviewedAt': None}]},
            'images': [{'localPath': path, 'sha256': HASH, 'size': len(PNG)} for path in ['images/back.png','images/front.png']]}
        normalized = normalize_document(value)
        self.assertEqual(normalized['snapshot']['cards'][0]['syncId'], 'legacy-card-8')
        self.assertEqual(normalized['snapshot']['cards'][0]['frontImageSha256'], HASH)

    def test_恢复包满足原应用清单规则且共享图片不丢引用(self):
        with tempfile.TemporaryDirectory() as temp:
            objects = Path(temp)/'objects'; objects.mkdir()
            (objects/f'{HASH}.png').write_bytes(PNG)
            archive = Path(temp)/'recovered.zip'
            make_recovery_zip('100-history', document(('甲','乙')), objects, archive)
            with zipfile.ZipFile(archive) as z:
                manifest=json.loads(z.read('manifest.json')); db=json.loads(z.read('database.json'))
                self.assertEqual(manifest['formatVersion'], 2)
                self.assertEqual(manifest['cardCount'], 2)
                self.assertEqual(manifest['imageCount'], 4)
                self.assertEqual(len(z.namelist()), 6)
                self.assertEqual({r['path'] for r in manifest['resources']}, set(z.namelist())-{'manifest.json'})
                for r in manifest['resources']:
                    data=z.read(r['path'])
                    self.assertEqual(len(data),r['size'])
                    self.assertEqual(hashlib.sha256(data).hexdigest(),r['sha256'])
                self.assertTrue(db['decks'][0]['name'].startswith('恢复副本'))

    def test_损坏对象不能生成可被误认为成功的恢复包(self):
        with tempfile.TemporaryDirectory() as temp:
            objects=Path(temp)/'objects'; objects.mkdir()
            (objects/f'{HASH}.png').write_bytes(b'bad')
            archive=Path(temp)/'recovered.zip'
            with self.assertRaises(ValueError): make_recovery_zip('100-history', document(), objects, archive)
            self.assertFalse(archive.exists())

if __name__=='__main__': unittest.main()
