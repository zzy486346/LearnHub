"""Replay already-seen calibration scores; never rewrite frozen first-run results."""
import argparse
import hashlib
import json
import math
import statistics
from pathlib import Path


def evaluate(rows, threshold):
    metrics = {category: {'queries': 0, 'queriesWithSources': 0, 'returnedChunks': 0}
               for category in ('ANSWERABLE', 'NO_EVIDENCE', 'CROSS_COURSE')}
    hits = 0
    reciprocal = 0
    missed = []
    predictions = {}
    for row in rows:
        qualified = {hit['chunkId'] for hit in row['semantic']
                     if math.isfinite(hit['score']) and threshold <= hit['score'] <= 1}
        found = [hit for hit in row['rrf'] if threshold == 0 or hit['chunkId'] in qualified][:5]
        predictions[row['id']] = found
        metric = metrics[row['category']]
        metric['queries'] += 1
        metric['queriesWithSources'] += bool(found)
        metric['returnedChunks'] += len(found)
        if row['category'] == 'ANSWERABLE':
            rank = next((i + 1 for i, hit in enumerate(found)
                         if hit['sourceKey'] in row['expectedSourceKeys']), 0)
            hits += rank > 0
            reciprocal += 1 / rank if rank else 0
            if not rank:
                missed.append(row['id'])
    metrics['ANSWERABLE'].update(sourceHitsAt5=hits, sourceMrrAt5=reciprocal / metrics['ANSWERABLE']['queries'],
                                 missedCaseIds=missed)
    return metrics, predictions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--validation')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    raw = Path(args.input).read_bytes()
    data = json.loads(raw)
    rows = data['results']
    assert len(rows) == 72 and len({row['id'] for row in rows}) == 72
    assert {key: sum(row['category'] == key for row in rows)
            for key in ('ANSWERABLE', 'NO_EVIDENCE', 'CROSS_COURSE')} == {
                'ANSWERABLE': 48, 'NO_EVIDENCE': 12, 'CROSS_COURSE': 12}
    distributions = {}
    for category in ('ANSWERABLE', 'NO_EVIDENCE', 'CROSS_COURSE'):
        distributions[category] = {}
        for channel in ('bm25', 'semantic', 'rrf'):
            scores = [row[channel][0]['score'] if row[channel] else 0
                      for row in rows if row['category'] == category]
            distributions[category][channel] = dict(min=min(scores), median=statistics.median(scores), max=max(scores))
    thresholds = (0, .80, .809, .81, .814, .815, .816, .817, .82, .85)
    sweep = [{'threshold': threshold, 'metrics': evaluate(rows, threshold)[0]} for threshold in thresholds]
    chosen = .815
    metrics, predicted = evaluate(rows, chosen)
    verified = False
    if args.validation:
        actual = json.loads(Path(args.validation).read_bytes())
        assert actual['minSemanticScore'] == chosen and len(actual['results']) == len(rows)
        for row in actual['results']:
            assert row['filtered'] == predicted[row['id']], row['id']
        verified = True
    report = dict(datasetId=data['datasetId'], usage='already-seen calibration, not an independent holdout',
                  rawScoresSha256=hashlib.sha256(raw).hexdigest(), rankConstant=60,
                  scoreDefinition='semantic=(1+cosineSimilarity)/2; RRF score is rank-only',
                  selectedThreshold=chosen, selectedCosineThreshold=round(2 * chosen - 1, 6),
                  distributions=distributions, sweep=sweep, selectedMetrics=metrics,
                  liveProductionPathMatchesReplay=verified, supplierCalls=0,
                  cases=[dict(id=row['id'], category=row['category'],
                              topBm25=row['bm25'][0]['score'] if row['bm25'] else 0,
                              topSemantic=row['semantic'][0]['score'] if row['semantic'] else 0,
                              topRrf=row['rrf'][0]['score'] if row['rrf'] else 0,
                              selectedSourceKeys=[hit['sourceKey'] for hit in predicted[row['id']]]) for row in rows])
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(threshold=chosen, liveVerified=verified, metrics=metrics), ensure_ascii=True))


if __name__ == '__main__':
    main()
