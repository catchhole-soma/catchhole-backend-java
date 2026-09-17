#!/usr/bin/env python3
"""검증된 도감 WebP만 업로드한다. 기존 객체/ACL/버킷 정책은 덮어쓰지 않는다."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import boto3
from botocore.exceptions import ClientError


def upload(manifest, root, bucket, region):
    entries = json.loads(manifest.read_text())['entries']
    assets = {e[k]['key']: e[k] for e in entries for k in ('thumbnail', 'image')}
    client = boto3.client('s3', region_name=region)
    client.head_bucket(Bucket=bucket)

    def publish(pair):
        key, meta = pair
        if key != f"world-image-catalog/v1/{meta['sha256']}.webp":
            raise ValueError('도감 경로 불일치')
        data = (root / key).read_bytes()
        if len(data) != meta['bytes'] or hashlib.sha256(data).hexdigest() != meta['sha256']:
            raise ValueError(f'원본 해시 불일치: {key}')
        created = False
        try:
            client.head_object(Bucket=bucket, Key=key)
        except ClientError as error:
            if error.response['Error']['Code'] not in ('404', 'NoSuchKey', 'NotFound'):
                raise
            try:
                client.put_object(Bucket=bucket, Key=key, Body=data, ContentType='image/webp',
                                  CacheControl='public,max-age=31536000,immutable',
                                  Metadata={'sha256': meta['sha256']}, IfNoneMatch='*')
                created = True
            except ClientError as put_error:
                if put_error.response['Error']['Code'] != 'PreconditionFailed':
                    raise
        # 이미 존재하는 객체도 실제 bytes를 대조한다. metadata만 신뢰하지 않는다.
        remote = client.get_object(Bucket=bucket, Key=key)
        try:
            verified = hashlib.sha256(remote['Body'].read()).hexdigest() == meta['sha256']
        finally:
            remote['Body'].close()
        if not verified or remote['ContentType'] != 'image/webp':
            raise ValueError(f'S3 검증 실패: {key}')
        return created

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(publish, assets.items()))
    report = {'verified': len(results), 'created': sum(results), 'bytes': sum(a['bytes'] for a in assets.values())}
    (root / 'upload-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[2] / 'scripts/world-images/manifests/catalog-v1.json')
    parser.add_argument('--bucket', default=os.environ.get('AWS_S3_BUCKET'))
    parser.add_argument('--region', default=os.environ.get('AWS_REGION', 'ap-northeast-2'))
    args = parser.parse_args()
    if not args.bucket:
        parser.error('--bucket 또는 AWS_S3_BUCKET이 필요합니다.')
    upload(args.manifest, args.root, args.bucket, args.region)
