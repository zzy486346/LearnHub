"""Verify every delivered clip rather than only checking file names."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import imageio_ffmpeg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    if len(manifest['courses']) != 21:
        raise ValueError('Expected 21 complete courses')
    hashes = set()
    total_bytes = total_seconds = 0
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    for course in manifest['courses']:
        if len(course['lessons']) != 2:
            raise ValueError('Expected two independent lessons')
        destination = root / 'courses' / course['slug']
        if not (destination / 'cover.svg').is_file():
            raise ValueError('Missing course cover')
        for number, lesson in enumerate(course['lessons'], start=1):
            video = destination / f'lesson-{number:02d}.mp4'
            digest = hashlib.sha256(video.read_bytes()).hexdigest()
            if digest in hashes:
                raise ValueError('A video was reused for unrelated lessons')
            hashes.add(digest)
            # Full decode checks video/audio corruption and missing streams.
            subprocess.run([ffmpeg, '-v', 'error', '-xerror', '-i', str(video), '-map', '0:v:0',
                            '-map', '0:a:0', '-f', 'null', '-'], check=True)
            frames = []
            for moment in [2, 5]:
                extracted = subprocess.run([ffmpeg, '-v', 'error', '-ss', str(moment), '-i', str(video),
                                            '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                                           capture_output=True, check=True)
                frames.append(hashlib.sha256(extracted.stdout).hexdigest())
            if frames[0] == frames[1]:
                raise ValueError('Expected actual motion, not a static image video')
            total_bytes += video.stat().st_size
            total_seconds += lesson['durationSeconds']
        print(f'Verified: {course["slug"]}', flush=True)
    print(json.dumps({'courses': len(manifest['courses']), 'uniqueVideos': len(hashes),
                      'durationSeconds': total_seconds, 'bytes': total_bytes}))


if __name__ == '__main__':
    main()
