"""Generate raster covers from the same course catalog for the OSS image API."""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw
from render import font, lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    catalog = json.loads((Path(__file__).parent / 'catalog.json').read_text(encoding='utf-8'))
    for course in catalog:
        image = Image.new('RGB', (960, 540), '#11172c')
        draw = ImageDraw.Draw(image)
        accent = course['accentHex']
        draw.ellipse((675, -65, 995, 255), fill=tuple(
            round(int(accent[i:i + 2], 16) * .16 + base * .84)
            for i, base in zip((1, 3, 5), (17, 23, 44))))
        points = [(650, 390), (735, 250), (825, 355), (900, 195)]
        draw.line(points, fill=accent, width=9)
        for x, y in points:
            draw.ellipse((x - 17, y - 17, x + 17, y + 17), fill=accent)
        draw.text((72, 61), 'LEARNHUB · 动画短课', font=font(24), fill='#b5c5ea')
        for number, title in enumerate(lines(course['title'], 12)):
            draw.text((72, 157 + number * 52), title, font=font(42, True), fill='white')
        draw.text((72, 333), course['subtitle'], font=font(24), fill='#bdc9e6')
        draw.rounded_rectangle((72, 428, 352, 482), radius=27, fill=accent)
        draw.text((102, 435), '2 节讲解 · 配音与图解', font=font(23), fill='white')
        destination = root / 'courses' / course['slug']
        destination.mkdir(parents=True, exist_ok=True)
        image.save(destination / 'cover.png')
    print(f'PNG covers: {len(catalog)}')


if __name__ == '__main__':
    main()
