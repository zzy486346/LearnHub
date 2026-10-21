import runpy
import unittest
from pathlib import Path

evaluate = runpy.run_path(str(Path(__file__).with_name('analyze-retrieval-threshold.py')))['evaluate']


class ThresholdReplayTest(unittest.TestCase):
    def row(self, score):
        hit = dict(chunkId=1, sourceKey='expected', score=score)
        return dict(id='answer', category='ANSWERABLE', semantic=[hit], rrf=[hit], expectedSourceKeys=['expected'])

    def test_exact_boundary_matches_production(self):
        self.assertEqual(evaluate([self.row(.815)], .815)[0]['ANSWERABLE']['sourceHitsAt5'], 1)
        self.assertEqual(evaluate([self.row(.814999)], .815)[0]['ANSWERABLE']['sourceHitsAt5'], 0)

    def test_non_finite_and_out_of_range_scores_cannot_pass(self):
        for score in (float('nan'), float('inf'), 1.01, -1):
            self.assertEqual(evaluate([self.row(score)], .815)[0]['ANSWERABLE']['sourceHitsAt5'], 0)

    def test_zero_keeps_the_ungated_baseline(self):
        self.assertEqual(evaluate([self.row(.1)], 0)[0]['ANSWERABLE']['sourceHitsAt5'], 1)


if __name__ == '__main__':
    unittest.main()
