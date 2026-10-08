"""Render original narrated course explainers. Generated media stays outside Git."""
import argparse
import functools
import hashlib
import json
import math
import re
import subprocess
import wave
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from xml.sax.saxutils import escape

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, FPS = 960, 540, 24
FONT = Path('C:/Windows/Fonts/msyh.ttc')
BOLD = Path('C:/Windows/Fonts/msyhbd.ttc')


@functools.lru_cache(maxsize=32)
def font(size, bold=False):
    return ImageFont.truetype(str(BOLD if bold else FONT), size)


def lines(text, limit):
    result, current = [], ''
    for character in text:
        candidate = current + character
        if len(candidate.encode('ascii', 'ignore')) / 2 + len(re.sub(r'[\x00-\x7f]', '', candidate)) > limit:
            result.append(current)
            current = character
        else:
            current = candidate
    if current:
        result.append(current)
    return result


def ease(value):
    value = min(1, max(0, value))
    return 1 - (1 - value) ** 3


@functools.lru_cache(maxsize=256)
def node_sprite(label, box_width, accent, active):
    sprite = Image.new('RGBA', (box_width + 2, 66), (0, 0, 0, 0))
    draw = ImageDraw.Draw(sprite)
    draw.rounded_rectangle((0, 0, box_width, 64), 14,
                           fill=accent if active else '#2e3b60', outline='#607098', width=1)
    node_font = font(19, True)
    bounds = draw.textbbox((0, 0), label, font=node_font)
    draw.text(((box_width - bounds[2]) / 2, 18), label, fill='white', font=node_font)
    return sprite


@functools.lru_cache(maxsize=256)
def caption_sprite(caption):
    sprite = Image.new('RGB', (852, 62), '#101729')
    draw = ImageDraw.Draw(sprite)
    for row, text in enumerate(lines(caption, 34)[:2]):
        caption_font = font(20)
        bounds = draw.textbbox((0, 0), text, font=caption_font)
        draw.text(((852 - bounds[2]) / 2, 4 + row * 25), text, fill='#f5f7ff', font=caption_font)
    return sprite


def scene_base(course, lesson, scene, number, total):
    base = Image.new('RGB', (WIDTH, HEIGHT), '#11172c')
    draw = ImageDraw.Draw(base)
    draw.rounded_rectangle((30, 24, 930, 510), 25, fill='#1a2340')
    draw.text((58, 42), 'LEARNHUB / 动画讲解短片', fill='#aebad7', font=font(17))
    draw.text((58, 79), course['title'], fill='white', font=font(30, True))
    draw.text((58, 126), lesson['title'], fill='#bcc8e4', font=font(21))
    draw.rounded_rectangle((58, 176, 63, 212), 2, fill=course['accentHex'])
    draw.text((80, 176), scene['heading'], fill='white', font=font(27, True))
    for row, text in enumerate(lines(scene['body'], 29)):
        draw.text((80, 220 + row * 32), text, fill='#ced7ed', font=font(23))
    draw.text((822, 46), f'{number:02d} / {total:02d}', fill=course['accentHex'], font=font(18, True))
    draw.text((60, 492), 'AI 编写讲解 · 合成配音 · 原创程序动画', fill='#8e9ab8', font=font(13))
    return base


def draw_frame(base, scene, course, elapsed, duration, progress):
    frame = base.copy()
    draw = ImageDraw.Draw(frame)
    labels = scene['diagram']
    count = len(labels)
    box_width = min(198, 760 // count - 16)
    start_x = (WIDTH - (count * box_width + (count - 1) * 30)) / 2
    for index, label in enumerate(labels):
        visible = ease((elapsed - 0.3 - index * 0.24) / 0.7)
        x = start_x + index * (box_width + 30)
        y = 330 + (1 - visible) * 25 + 2 * math.sin(elapsed * 1.7 + index)
        active = int((elapsed / max(duration, 1) * count) % count) == index
        node = node_sprite(label, box_width, course['accentHex'], active)
        frame.paste(node, (round(x), round(y)), node)
        if index < count - 1:
            arrow_x = x + box_width + 8
            draw.line((arrow_x, y + 32, arrow_x + 15, y + 32), fill='#92a4d0', width=2)
            draw.polygon([(arrow_x + 15, y + 32), (arrow_x + 10, y + 27), (arrow_x + 10, y + 37)], fill='#92a4d0')
            dot_x = arrow_x + (elapsed * 28 % 16)
            draw.ellipse((dot_x - 2, y + 30, dot_x + 2, y + 34), fill=course['accentHex'])
    captions = [fragment for part in re.split(r'(?<=[。！？；])', scene['narration'])
                if part.strip() for fragment in lines(part, 34)]
    total_chars = sum(map(len, captions)) or 1
    char_position = elapsed / max(duration, 1) * total_chars
    consumed, caption = 0, captions[-1] if captions else ''
    for part in captions:
        consumed += len(part)
        if char_position <= consumed:
            caption = part
            break
    frame.paste(caption_sprite(caption), (54, 420))
    draw.rounded_rectangle((60, 519, 900, 523), 2, fill='#293653')
    draw.rounded_rectangle((60, 519, 60 + max(2, 840 * progress), 523), 2, fill=course['accentHex'])
    fade = min(ease(elapsed / 0.45), ease((duration - elapsed) / 0.4))
    if fade < 1:
        frame = Image.blend(Image.new('RGB', frame.size, '#11172c'), frame, fade)
    return frame


def cover(course):
    title = ''.join(f'<tspan x="72" dy="52">{escape(line)}</tspan>' for line in lines(course['title'], 12))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540" viewBox="0 0 960 540">
<rect width="960" height="540" rx="28" fill="#11172c"/>
<circle cx="835" cy="95" r="160" fill="{course['accentHex']}" opacity=".16"/>
<path d="M650 390 L735 250 L825 355 L900 195" fill="none" stroke="{course['accentHex']}" stroke-width="9" stroke-linecap="round"/>
<g fill="{course['accentHex']}"><circle cx="650" cy="390" r="17"/><circle cx="735" cy="250" r="17"/><circle cx="825" cy="355" r="17"/><circle cx="900" cy="195" r="17"/></g>
<text x="72" y="88" fill="#b5c5ea" font-family="Microsoft YaHei,sans-serif" font-size="24">LEARNHUB · 动画短课</text>
<text x="72" y="155" fill="white" font-family="Microsoft YaHei,sans-serif" font-size="42" font-weight="700">{title}</text>
<text x="72" y="362" fill="#bdc9e6" font-family="Microsoft YaHei,sans-serif" font-size="24">{escape(course['subtitle'])}</text>
<rect x="72" y="428" width="280" height="54" rx="27" fill="{course['accentHex']}"/>
<text x="102" y="463" fill="white" font-family="Microsoft YaHei,sans-serif" font-size="23">2 节讲解 · 配音与图解</text></svg>'''


def render(course, lesson, lesson_index, root, ffmpeg):
    work = root / '_work' / course['slug']
    destination = root / 'courses' / course['slug']
    destination.mkdir(parents=True, exist_ok=True)
    combined = work / f'lesson-{lesson_index:02d}.wav'
    durations = []
    with wave.open(str(combined), 'wb') as output:
        for index in range(len(lesson['scenes'])):
            narration_hash = hashlib.sha256(lesson['scenes'][index]['narration'].encode()).hexdigest()[:12]
            with wave.open(str(work / f'lesson-{lesson_index:02d}-scene-{index+1:02d}-{narration_hash}.wav'), 'rb') as source:
                if index == 0:
                    output.setparams(source.getparams())
                elif source.getparams()[:3] != output.getparams()[:3]:
                    raise ValueError('Inconsistent narration format')
                durations.append(source.getnframes() / source.getframerate())
                output.writeframes(source.readframes(source.getnframes()))
    video = destination / f'lesson-{lesson_index:02d}.mp4'
    command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
               '-s', f'{WIDTH}x{HEIGHT}', '-r', str(FPS), '-i', 'pipe:0', '-i', str(combined),
               '-c:v', 'libx264', '-threads', '2', '-preset', 'veryfast', '-crf', '25', '-pix_fmt', 'yuv420p',
               '-c:a', 'aac', '-b:a', '96k', '-af', 'loudnorm=I=-18:TP=-2:LRA=7',
               '-movflags', '+faststart', '-shortest', str(video)]
    total = sum(durations)
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    elapsed_total = 0
    try:
        for index, (scene, duration) in enumerate(zip(lesson['scenes'], durations)):
            base = scene_base(course, lesson, scene, index + 1, len(durations))
            frames = math.ceil(duration * FPS)
            for number in range(frames):
                elapsed = number / FPS
                frame = draw_frame(base, scene, course, elapsed, duration, (elapsed_total + elapsed) / total)
                process.stdin.write(frame.tobytes())
            elapsed_total += duration
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError(f'Video encoder failed: {video}')
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()
    probe = subprocess.run([ffmpeg, '-hide_banner', '-i', str(video)], capture_output=True, text=True)
    match = re.search(r'Duration: (\d+):(\d+):(\d+\.\d+)', probe.stderr)
    if not match or 'Video: h264' not in probe.stderr or 'Audio: aac' not in probe.stderr:
        raise RuntimeError('Generated media has invalid streams')
    actual = int(match[1]) * 3600 + int(match[2]) * 60 + float(match[3])
    return {'url': f'http://localhost:8091/courses/{course["slug"]}/lesson-{lesson_index:02d}.mp4',
            'durationSeconds': math.ceil(actual), 'bytes': video.stat().st_size}


def render_course(course, root, ffmpeg, source_hash):
    item = {'slug': course['slug'], 'sourceHash': source_hash,
            'coverUrl': f'http://localhost:8091/courses/{course["slug"]}/cover.svg', 'lessons': []}
    for index, lesson in enumerate(course['lessons']):
        item['lessons'].append(render(course, lesson, index + 1, root, ffmpeg))
        print(f'Rendered: {course["slug"]}/{index+1}', flush=True)
    return item


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--workers', type=int, default=3)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    catalog = json.loads((Path(__file__).parent / 'catalog.json').read_text(encoding='utf-8'))
    if args.limit:
        catalog = catalog[:args.limit]
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    manifest = {'format': 'AI-authored / local synthesized narration / procedural motion graphics', 'courses': []}
    prior_path = root / 'manifest.json'
    prior = json.loads(prior_path.read_text(encoding='utf-8')) if prior_path.exists() else {'courses': []}
    existing = {item['slug']: item for item in prior['courses']}
    pending = []
    for course in catalog:
        source_hash = hashlib.sha256(json.dumps(course, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        destination = root / 'courses' / course['slug']
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'cover.svg').write_text(cover(course), encoding='utf-8')
        cached = existing.get(course['slug'])
        if cached and cached.get('sourceHash') == source_hash and all((destination / f'lesson-{n+1:02d}.mp4').exists() for n in range(len(course['lessons']))):
            manifest['courses'].append(cached)
            print(f'Reused: {course["slug"]}', flush=True)
            continue
        pending.append((course, source_hash))
    ordering = {course['slug']: index for index, course in enumerate(catalog)}
    with ProcessPoolExecutor(max_workers=max(1, min(args.workers, 4))) as executor:
        futures = [executor.submit(render_course, course, root, ffmpeg, source_hash) for course, source_hash in pending]
        for future in as_completed(futures):
            manifest['courses'].append(future.result())
            manifest['courses'].sort(key=lambda item: ordering[item['slug']])
            (root / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (root / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Complete: {len(manifest["courses"])} courses', flush=True)


if __name__ == '__main__':
    main()
