#!/usr/bin/env python3
"""작품별 이미지 보정. 기본은 미리보기이며 --apply를 지정해야 저장한다."""
import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-base', required=True, help='예: https://api.example.com (끝에 /api/v1 제외)')
    parser.add_argument('--work-id', required=True)
    parser.add_argument('--kind', choices=['CHARACTER', 'WORLD_SETTING'], required=True)
    parser.add_argument('--batch-size', type=int, default=100)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 500:
        parser.error('batch-size는 1~500이어야 합니다.')
    parsed = urllib.parse.urlsplit(args.api_base)
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', '::1')):
        parser.error('원격 API는 HTTPS를 사용해야 합니다.')
    token = os.environ.get('CATCHHOLE_ADMIN_ACCESS_TOKEN')
    if not token:
        parser.error('CATCHHOLE_ADMIN_ACCESS_TOKEN 환경변수가 필요합니다. 토큰은 출력하지 않습니다.')
    total = 0
    while True:
        payload = dict(workId=args.work_id, kind=args.kind, limit=args.batch_size, apply=args.apply)
        request = urllib.request.Request(args.api_base.rstrip('/')+'/api/v1/admin/world-images/backfill',
                data=json.dumps(payload).encode(), headers={'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'}, method='POST')
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)['data']
        except urllib.error.HTTPError as error:
            # 서버 body·인증 헤더·대상 설정 내용은 출력하지 않는다.
            raise SystemExit(f'보정 요청 실패: HTTP {error.code}. 완료된 묶음은 유지됩니다. 같은 명령으로 재개할 수 있습니다.') from None
        total += result['processed']
        print(json.dumps({'kind': args.kind, **result, 'totalProcessed': total}, ensure_ascii=False), flush=True)
        if not args.apply or result['processed'] == 0:
            break
        time.sleep(0.2)


if __name__ == '__main__':
    main()
