#!/usr/bin/env python3
"""승인된 로컬 도감을 WebP, 등록 manifest, Flyway seed로 변환한다. 원본은 변경하지 않는다."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import unicodedata
from PIL import Image, ImageOps

CATEGORIES = {
    'races': ('RACE', 'race', '종족'), 'factions': ('FACTION', 'faction', '세력'),
    'places': ('LOCATION', 'location', '장소'), 'monsters': ('MONSTER', 'monster', '몬스터'),
    'magic-abilities': ('POWER_SYSTEM', 'power-system', '마법·능력'),
    'rules-history': ('WORLD_RULE_HISTORY', 'history', '규칙·역사'),
    'items': ('IMPORTANT_ITEM', 'item', '중요 아이템'),
}


def normalize(value):
    return re.sub(r'[\s·ㆍ._/\\-]+', '', unicodedata.normalize('NFC', value).lower())


def sql(value):
    return "'" + str(value).replace("'", "''") + "'"


def build(workspace, output, repo):
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for directory, (category, prefix, label) in CATEGORIES.items():
        indices = sorted((workspace / 'design-assets/world-subjects' / directory).glob('*/ASSET_INDEX.json'))
        if len(indices) != 1:
            raise ValueError(f'{directory}: 사용할 도감 버전을 명시해야 합니다.')
        index_path = indices[0]
        sources = [a for a in json.loads(index_path.read_text())['assets'] if a.get('current_image')]
        for a in sources:
            slug = re.sub(r'^\d+-', '', a['id'])
            entries.append({
                'id': prefix + '-' + slug, 'category': category, 'name': a['name'],
                'aliases': sorted(set(a.get('aliases', []))), 'default': False,
                'source': str((index_path.parent / a['current_image']).relative_to(workspace)),
            })
        entries.append({
            'id': prefix + '-default', 'category': category, 'name': label + ' 기본 이미지',
            'aliases': [], 'default': True,
            'source': f'catchhole-front/src/assets/world-categories/{prefix}.webp',
        })
    assert len(entries) == 340 and len({e['id'] for e in entries}) == 340
    aliases = defaultdict(set)
    for entry in entries:
        source = workspace / entry['source']
        entry['sourceSha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with Image.open(source) as original:
            image = ImageOps.exif_transpose(original).convert('RGB')
            for name, width, quality in [('thumbnail', 480, 78), ('image', 960, 82)]:
                resized = ImageOps.fit(image, (width, width * 2 // 3), method=Image.Resampling.LANCZOS)
                temp = output / (entry['id'] + '-' + name + '.webp')
                resized.save(temp, 'WEBP', quality=quality, method=6)
                data = temp.read_bytes()
                sha = hashlib.sha256(data).hexdigest()
                key = f'world-image-catalog/v1/{sha}.webp'
                destination = output / key
                destination.parent.mkdir(parents=True, exist_ok=True)
                if destination.exists():
                    assert destination.read_bytes() == data
                    temp.unlink()
                else:
                    temp.rename(destination)
                entry[name] = {'key': key, 'sha256': sha, 'bytes': len(data), 'width': width, 'height': width * 2 // 3}
        entry['searchText'] = normalize(' '.join([entry['name']] + entry['aliases']))
        for term in [entry['name']] + entry['aliases']:
            aliases[(entry['category'], normalize(term))].add(entry['id'])
    manifest = {'version': 1, 'entries': entries}
    manifest_path = repo / 'src/main/resources/world-images/catalog-v1.json'
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    statements = ['-- scripts/world-images/build_catalog.py에서 생성. 적용된 migration은 수정하지 않는다.']
    for e in entries:
        values = [sql(e[k]) for k in ['id', 'category', 'name', 'searchText']]
        values += [str(e['default']).lower(), 'true', sql(e['thumbnail']['sha256']), sql(e['image']['sha256'])]
        statements.append('INSERT INTO world_image_catalog (id, category, name, search_text, is_default, active, thumbnail_sha, image_sha) VALUES (' + ', '.join(values) + ');')
        for alias in e['aliases']:
            statements.append('INSERT INTO world_image_aliases (catalog_id, alias) VALUES (' + sql(e['id']) + ', ' + sql(alias) + ');')
    seed_path = repo / 'src/main/resources/db/migration/V56__seed_world_image_catalog.sql'
    seed_sql = '\n'.join(statements) + '\n'
    if seed_path.exists() and seed_path.read_text() != seed_sql:
        raise ValueError('기존 V56 migration과 달라졌습니다. 적용된 seed를 덮어쓰지 말고 새 migration/도감 버전을 만드세요.')
    seed_path.write_text(seed_sql)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    report = {
        'entryCount': len(entries), 'categories': dict(Counter(e['category'] for e in entries)),
        'defaultCount': sum(e['default'] for e in entries),
        'aliasCount': sum(len(e['aliases']) for e in entries),
        'collisions': [{'category': c, 'normalizedAlias': term, 'candidates': sorted(ids)} for (c, term), ids in aliases.items() if len(ids) > 1],
        'assetBytes': sum(p.stat().st_size for p in (output / 'world-image-catalog/v1').glob('*.webp')),
    }
    (output / 'build-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'collisions'}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.workspace.resolve(), args.output.resolve(), Path(__file__).resolve().parents[2])
