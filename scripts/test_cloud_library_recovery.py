import copy
import unittest
from cloud_library_recovery import merge_recovered, make_index, encoded


def document(identifier, key):
    return dict(formatVersion=2, snapshot=dict(decks=[dict(syncId='deck',name='示例',createdAt=1,updatedAt=1)],
        cards=[dict(syncId=identifier,deckSyncId='deck',keyText=key,createdAt=1,updatedAt=1,archived=False,valueImageSha256='a'*64)],
        reviews=[dict(cardSyncId=identifier,weight=1.0,seenCount=0,againCount=0,unsureCount=0,knownCount=0,lastReviewedAt=None)]),
        images=[dict(sha256='a'*64,size=100)])


class RecoveryLibraryTest(unittest.TestCase):
    def test_preserves_original_records_and_recovery_is_idempotent(self):
        original=document('old','原卡'); recovered=document('lost','非归零编码')
        result=merge_recovered(original,recovered,{'deck':'deck'},{'lost':'非归零编码'},100)
        self.assertEqual(result['snapshot']['cards'][0],original['snapshot']['cards'][0])
        self.assertEqual(len(result['snapshot']['cards']),2)
        self.assertEqual(merge_recovered(result,recovered,{'deck':'deck'},{'lost':'非归零编码'},200),result)
        self.assertEqual(len(result['images']),1)

    def test_never_overwrites_recovered_record_that_user_later_edited(self):
        result=merge_recovered(document('old','原卡'),document('lost','卡'),{'deck':'deck'},{'lost':'非归零编码'},100)
        result['snapshot']['cards'][-1]['keyText']='用户已修改'
        with self.assertRaisesRegex(ValueError,'内容不同'):
            merge_recovered(result,document('lost','卡'),{'deck':'deck'},{'lost':'非归零编码'},200)

    def test_deck_mapping_must_be_explicit_and_existing(self):
        with self.assertRaisesRegex(ValueError,'目标卡组不存在'):
            merge_recovered(document('old','原卡'),document('lost','卡'),{'deck':'missing'},{'lost':'卡'},100)

    def test_index_caches_deck_state_metadata_counts_and_shared_image_bytes(self):
        doc=document('old','原卡')
        doc['snapshot']['decks'][0].update(parentSyncId=None, archived=True)
        doc['snapshot']['cards'][0]['archived']=True
        index=make_index(doc,100,'revision',[])
        self.assertEqual(index['decks'][0]['archivedCardCount'],1)
        self.assertEqual(index['decks'][0]['cardCount'],0)
        self.assertEqual(index['decks'][0]['imageBytes'],100)
        self.assertTrue(index['decks'][0]['archived'])
        self.assertIn('原卡'.encode(),encoded(index))


if __name__=='__main__': unittest.main()
