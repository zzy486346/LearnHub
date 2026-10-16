import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { auditMapping } from './audit-stage-a-mapping.mjs'

const directory = dirname(fileURLToPath(import.meta.url))
const catalog = JSON.parse(readFileSync(resolve(directory, '..', 'course-media', 'catalog.json'), 'utf8'))
const mapping = JSON.parse(readFileSync(resolve(directory, 'stage-a-mapping.json'), 'utf8'))
const rows = mapping.mappings.map(item => ({
  courseId: item.courseId,
  lessonId: item.lessonId,
  courseSlug: item.courseSlug,
  lessonTitle: item.lessonTitle,
  courseDeleted: 0,
  lessonDeleted: 0,
}))

test('经核验的三门课程六个课时与 catalog 一一对应', () => {
  assert.deepEqual(auditMapping(catalog, mapping, rows), { courses: 3, documents: 6, scenes: 18 })
})

test('拒绝标题漂移、删除课时和重复数据库行', () => {
  assert.throws(() => auditMapping(catalog, mapping, rows.map((row, index) => index === 0 ? { ...row, lessonTitle: '漂移' } : row)), /课时标题不一致/)
  assert.throws(() => auditMapping(catalog, mapping, rows.map((row, index) => index === 0 ? { ...row, lessonDeleted: 1 } : row)), /已删除/)
  assert.throws(() => auditMapping(catalog, mapping, [...rows, rows[0]]), /重复课程课时/)
})
