#!/usr/bin/env python3
"""Render compressed launcher PNGs and generate native adaptive vector layers."""
from pathlib import Path
import subprocess
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name('camera-agent.svg')
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
SVG = '{http://www.w3.org/2000/svg}'
BACKGROUND = '#07552F'

def attrs(**values):
    return {'{' + ANDROID + '}' + key: str(value) for key, value in values.items()}

def vector(monochrome=False):
    root = ET.Element('vector', attrs(width='108dp', height='108dp', viewportWidth=108, viewportHeight=108))
    group = ET.SubElement(root, 'group', attrs(scaleX='.92', scaleY='.92', translateX='4.32', translateY='4.32'))
    def visit(element, inherited, parent):
        style = inherited | element.attrib
        tag = element.tag.removeprefix(SVG)
        if tag == 'g' and 'transform' in style:
            match = re.fullmatch(r'translate\(([-\d.]+)[ ,]+([-\d.]+)\)\s*scale\(([-\d.]+)(?:[ ,]+([-\d.]+))?\)', style['transform'])
            if not match:
                raise ValueError(f"Unsupported SVG group transform: {style['transform']}")
            tx, ty, scale_x, scale_y = match.groups()
            parent = ET.SubElement(parent, 'group', attrs(scaleX=scale_x, scaleY=scale_y or scale_x, translateX=tx, translateY=ty))
        path = style.get('d')
        if tag == 'circle':
            x, y, r = (float(style[k]) for k in ('cx', 'cy', 'r'))
            path = f'M{x-r},{y}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0'
        if path:
            # The monochrome launcher should keep the lens opening transparent.
            values = dict(pathData=path, fillColor='#FFFFFF' if monochrome and style.get('fill') == '#F7F5EC' else style.get('fill', '#00000000'))
            if monochrome and style.get('fill') != '#F7F5EC':
                values['fillColor'] = '#00000000'
            if values['fillColor'] == 'none': values['fillColor'] = '#00000000'
            if 'stroke' in style:
                values.update(strokeColor='#FFFFFF' if monochrome else style['stroke'], strokeWidth=style.get('stroke-width', '4'), strokeLineCap='round', strokeLineJoin='round')
            ET.SubElement(parent, 'path', attrs(**values))
        for child in element:
            if child.tag != SVG + 'rect': visit(child, style, parent)
    visit(ET.parse(SOURCE).getroot(), {}, group)
    ET.indent(root)
    return ET.tostring(root, encoding='unicode') + '\n'

for relative in ['android-8.0-camera-agent/app/src/main/res', 'android-4.0-camera-agent/android/app/src/main/res']:
    res = ROOT / relative
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        folder = res / ('mipmap-' + density)
        folder.mkdir(exist_ok=True)
        subprocess.run([
            'magick', '-background', 'none', str(SOURCE), '-resize', f'{size}x{size}',
            '-depth', '8', '-strip', '-define', 'png:compression-level=9',
            str(folder / 'ic_launcher.png')
        ], check=True)
    (res / 'drawable/ic_launcher_foreground.xml').write_text(vector())
    (res / 'drawable/ic_launcher_monochrome.xml').write_text(vector(True))
    (res / 'drawable/ic_launcher_background.xml').write_text('<shape xmlns:android="' + ANDROID + '"><solid android:color="' + BACKGROUND + '"/></shape>\n')
    for qualifier in ['v26', 'v33']:
        folder = res / ('mipmap-anydpi-' + qualifier)
        folder.mkdir(exist_ok=True)
        monochrome = '\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' if qualifier == 'v33' else ''
        (folder / 'ic_launcher.xml').write_text('<adaptive-icon xmlns:android="' + ANDROID + '">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />' + monochrome + '\n</adaptive-icon>\n')
