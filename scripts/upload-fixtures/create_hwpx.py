"""Complete the hwpxlib blank fixture with synthetic prose, preserving package/header metadata."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED
root = Path(sys.argv[1])
with ZipFile(root / 'blank.hwpx') as source:
    parts = {name: source.read(name) for name in source.namelist()}
hp = 'http://www.hancom.co.kr/hwpml/2011/paragraph'
sentences = ['제 1화 새벽의 편지', '서윤은 성문 앞에서 편지를 읽었다.', '제 2화 다시 만난 길', '도윤은 오래된 약속을 떠올렸다.']
for name, lines in [('episode-1.hwpx', sentences[:2]), ('episode-2.hwpx', sentences[2:]), ('two-episodes.hwpx', sentences)]:
    section = ET.fromstring(parts['Contents/section0.xml'])
    paragraph = section.find(f'{{{hp}}}p')
    run = paragraph.find(f'{{{hp}}}run')
    for t in list(run.findall(f'{{{hp}}}t')):
        run.remove(t)
    ET.SubElement(run, f'{{{hp}}}t').text = lines[0]
    for line in lines[1:]:
        p = ET.SubElement(section, f'{{{hp}}}p', paragraph.attrib)
        r = ET.SubElement(p, f'{{{hp}}}run', run.attrib)
        ET.SubElement(r, f'{{{hp}}}t').text = line
    with ZipFile(root / name, 'w', ZIP_DEFLATED) as target:
        for key, value in parts.items():
            target.writestr(key, ET.tostring(section, encoding='utf-8', xml_declaration=True) if key == 'Contents/section0.xml' else value)
