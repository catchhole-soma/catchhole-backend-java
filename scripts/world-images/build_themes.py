#!/usr/bin/env python3
"""장르 구성표를 공용 자산·다대다 추천·초기/기본 슬롯으로 변환한다. V56은 보존한다."""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
from PIL import Image, ImageOps
from build_catalog import normalize, sql


def collect_ids(value):
    if isinstance(value, dict):
        if 'id' in value and 'source' in value:
            yield value['id']
        for item in value.values():
            yield from collect_ids(item)
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, str):
                continue
            yield from collect_ids(item)


def build(workspace, output, repo):
    old = json.loads((repo / 'src/main/resources/world-images/catalog-v1.json').read_text())
    catalog = {e['id']: e for e in old['entries']}
    originals = set(catalog)
    encoded = {}

    def encode(source):
        if source in encoded:
            return encoded[source]
        path = workspace / source
        result = {'source': source, 'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        with Image.open(path) as original:
            picture = ImageOps.exif_transpose(original).convert('RGB')
            for kind, width, quality in [('thumbnail', 480, 78), ('image', 960, 82)]:
                stream = io.BytesIO()
                ImageOps.fit(picture, (width, width * 2 // 3), method=Image.Resampling.LANCZOS).save(stream, 'WEBP', quality=quality, method=6)
                data = stream.getvalue()
                sha = hashlib.sha256(data).hexdigest()
                key = f'world-image-catalog/v1/{sha}.webp'
                target = output / key
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists():
                    assert target.read_bytes() == data
                else:
                    target.write_bytes(data)
                result[kind] = {'key': key, 'sha256': sha, 'bytes': len(data), 'width': width, 'height': width * 2 // 3}
        encoded[source] = result
        return result

    # 기존 그림은 bytes와 ID를 그대로 공유한다. 재압축하지 않는다.
    for e in old['entries']:
        encoded[e['source']] = {k: e[k] for k in ['source', 'sourceSha256', 'thumbnail', 'image']}
    plans = {}
    for folder in sorted((workspace / 'design-assets/world-themes').glob('*/2026-09-16-v1')):
        if not (folder / 'THEME_PLAN.json').exists():
            continue
        plan = json.loads((folder / 'THEME_PLAN.json').read_text())
        plans[plan['theme_id']] = plan
        assets = {a['id']: a for a in json.loads((folder / 'ASSET_INDEX.json').read_text())['assets']}
        for item in plan['catalog']['new_recommended']:
            a = assets[item['id']]
            assert a['id'] not in catalog
            aliases = sorted(set(a['aliases']))
            catalog[a['id']] = dict(id=a['id'], category=a['category'], name=a['name'], aliases=aliases,
                                    default=False, searchText=normalize(' '.join([a['name']] + aliases)), **encode(item['source']))

    default_files = ['01-race-default.png','02-faction-default.png','03-location-default.png',
                     '04-monster-default-v4.png','05-power-system-default.png','06-history-default.png','07-item-default.png']
    categories = ['RACE','FACTION','LOCATION','MONSTER','POWER_SYSTEM','WORLD_RULE_HISTORY','IMPORTANT_ITEM']
    prefixes = ['race','faction','location','monster','power-system','history','item']
    fantasy = {'overview': {}, 'defaults': {}}
    for category, prefix, file in zip(categories, prefixes, default_files):
        e = catalog[prefix + '-default']
        e.update(encode('design-assets/world-defaults/2026-09-15-v1/' + file))
        fantasy['defaults'][category] = e
        fantasy['overview'][category] = dict(name=e['name'], source=f'catchhole-front/src/assets/world-categories/{prefix}.webp')
    fantasy['overview']['ALL'] = dict(name='판타지 전체', source='catchhole-front/src/assets/world-categories/all.webp')
    plans['fantasy'] = fantasy

    recommendations = {(e['id'], 'fantasy') for e in old['entries'] if not e['default']}
    for theme, plan in plans.items():
        if theme == 'fantasy':
            continue
        ids = set(collect_ids(plan['catalog'])) | set(plan['catalog'].get('included_ids', []))
        assert all(i in catalog and not catalog[i]['default'] for i in ids), (theme, ids - catalog.keys())
        recommendations.update((i, theme) for i in ids)
    slots = []
    for theme, plan in plans.items():
        assert len(plan['overview']) == 8 and len(plan['defaults']) == 7
        for purpose, name in [('OVERVIEW','overview'),('DEFAULT','defaults')]:
            for slot, item in plan[name].items():
                slots.append(dict(id=f'{theme}-{purpose.lower()}-{slot.lower()}', theme=theme, purpose=purpose,
                                  slot=slot, name=item['name'], **encode(item['source'])))
    statements = ['-- build_themes.py에서 생성. V56과 기존 선택 행은 보존한다.']
    for e in catalog.values():
        if e['id'] in originals:
            if e['default']:
                statements.append(f"UPDATE world_image_catalog SET thumbnail_sha={sql(e['thumbnail']['sha256'])}, image_sha={sql(e['image']['sha256'])} WHERE id={sql(e['id'])};")
            continue
        values = [sql(e[k]) for k in ['id','category','name','searchText']]
        values += ['false','true',sql(e['thumbnail']['sha256']),sql(e['image']['sha256'])]
        statements.append('INSERT INTO world_image_catalog (id,category,name,search_text,is_default,active,thumbnail_sha,image_sha) VALUES ('+', '.join(values)+');')
        for alias in e['aliases']:
            statements.append(f"INSERT INTO world_image_aliases(catalog_id,alias) VALUES ({sql(e['id'])},{sql(alias)});")
    for asset in slots:
        values = [sql(asset[k]) for k in ['id','theme','purpose','slot','name']]
        values += [sql(asset['thumbnail']['sha256']),sql(asset['image']['sha256'])]
        statements.append('INSERT INTO world_image_theme_assets(id,theme,purpose,slot,name,thumbnail_sha,image_sha) VALUES ('+', '.join(values)+');')
    for id, theme in sorted(recommendations):
        statements.append(f'INSERT INTO world_image_recommendations(catalog_id,theme) VALUES ({sql(id)},{sql(theme)});')
    seed = '\n'.join(statements)+'\n'
    target = repo/'src/main/resources/db/migration/V60__seed_world_image_themes.sql'
    if target.exists() and target.read_text() != seed:
        raise ValueError('V60 결과가 달라졌습니다. 적용된 migration은 덮어쓸 수 없습니다.')
    target.write_text(seed)
    manifest = {'version':2,'entries':list(catalog.values())+slots,'themeSlots':slots,
                'recommendations':[{'catalogId':i,'theme':t} for i,t in sorted(recommendations)]}
    (repo/'src/main/resources/world-images/themes-v1.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    report = {'catalog':len(catalog),'newCatalog':len(catalog)-len(originals),'themeSlots':len(slots),
              'recommendations':dict(Counter(t for _,t in recommendations)),
              'newAssetFiles':len(list((output/'world-image-catalog/v1').glob('*.webp')))}
    (output/'build-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    build(args.workspace.resolve(),args.output.resolve(),Path(__file__).resolve().parents[2])
