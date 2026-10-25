"""为安全同步的新索引重建恢复记录。输入输出均为本地文件，不包含网络凭据。"""
import copy
import hashlib
import json
import uuid
from cloud_backup_recovery import normalize_document


def merge_recovered(current, recovered, deck_mapping, key_mapping, timestamp):
    current = normalize_document(current)
    recovered = normalize_document(recovered)
    result = copy.deepcopy(current)
    decks = {row['syncId']: row for row in result['snapshot']['decks']}
    cards = {row['syncId']: row for row in result['snapshot']['cards']}
    reviews = {row['cardSyncId']: row for row in result['snapshot']['reviews']}
    for old in recovered['snapshot']['cards']:
        target = deck_mapping[old['deckSyncId']]
        if target not in decks:
            raise ValueError('恢复目标卡组不存在，禁止自动猜测或覆盖')
        title = key_mapping[old['syncId']].strip()
        if not title:
            raise ValueError('恢复 key 不能为空')
        identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, 'mutsumi-card/recovery/' + old['syncId'] + '/' + old['valueImageSha256']))
        card = dict(old, syncId=identifier, deckSyncId=target, keyText=title, createdAt=timestamp, updatedAt=timestamp)
        # 重复执行只接受完全相同的恢复内容，不覆盖恢复后已经编辑的卡片。
        if identifier in cards:
            existing = cards[identifier]
            if any(existing.get(k) != card.get(k) for k in ('deckSyncId','keyText','valueImageSha256','frontImageSha256','archived')):
                raise ValueError('恢复记录已存在且内容不同，停止覆盖')
            continue
        cards[identifier] = card
        reviews[identifier] = dict(cardSyncId=identifier, weight=1.0, seenCount=0, againCount=0, unsureCount=0, knownCount=0, lastReviewedAt=None)
    refs = {row['sha256']: row for row in current['images'] + recovered['images']}
    used = {value for card in cards.values() for value in (card['valueImageSha256'],card.get('frontImageSha256')) if value}
    result['snapshot']['cards'] = list(cards.values())
    result['snapshot']['reviews'] = list(reviews.values())
    result['images'] = [refs[digest] for digest in sorted(used)]
    return normalize_document(result)


def deck_index(document):
    refs = {row['sha256']:row['size'] for row in document['images']}
    grouped = {}
    for card in document['snapshot']['cards']:
        grouped.setdefault(card['deckSyncId'], []).append(card)
    result = []
    for deck in document['snapshot']['decks']:
        cards = grouped.get(deck['syncId'], [])
        hashes = {digest for card in cards for digest in (card['valueImageSha256'], card.get('frontImageSha256')) if digest}
        result.append(dict(syncId=deck['syncId'], name=deck['name'], parentSyncId=deck.get('parentSyncId'), archived=deck.get('archived',False),
            cardCount=sum(not card['archived'] for card in cards), archivedCardCount=sum(card['archived'] for card in cards),
            imageBytes=sum(refs[digest] for digest in hashes), updatedAt=max([deck['updatedAt']]+[card['updatedAt'] for card in cards])))
    return result


def make_index(document, timestamp, revision, snapshots):
    document = normalize_document(document)
    return dict(formatVersion=3, revision=revision, updatedAt=timestamp, document=document, decks=deck_index(document), snapshots=snapshots[:8])


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',',':'), allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(value).hexdigest()
