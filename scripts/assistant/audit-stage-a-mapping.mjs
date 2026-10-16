import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { prepareDocuments } from './prepare-documents.mjs'

function readJson(path, label) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch (error) {
    throw new Error(`无法读取${label}：${error instanceof Error ? error.message : String(error)}`)
  }
}

export function auditMapping(catalog, mapping, databaseRows) {
  const payloads = prepareDocuments(catalog, mapping)
  if (!Array.isArray(databaseRows)) throw new Error('数据库快照必须是数组')
  const rows = new Map()
  for (const [index, row] of databaseRows.entries()) {
    if (row === null || typeof row !== 'object' || Array.isArray(row)) throw new Error(`数据库快照[${index}]必须是对象`)
    const key = `${String(row.courseId)}\u0000${String(row.lessonId)}`
    if (rows.has(key)) throw new Error(`数据库快照存在重复课程课时：${row.courseId}/${row.lessonId}`)
    rows.set(key, row)
  }
  for (const payload of payloads) {
    const row = rows.get(`${payload.courseId}\u0000${payload.lessonId}`)
    if (!row) throw new Error(`数据库中不存在映射：${payload.courseId}/${payload.lessonId}`)
    if (row.courseSlug !== mapping.mappings.find(item => item.lessonId === payload.lessonId)?.courseSlug) {
      throw new Error(`courseSlug 不一致：${payload.courseId}/${payload.lessonId}`)
    }
    if (row.lessonTitle !== payload.title) throw new Error(`课时标题不一致：${payload.lessonId}`)
    if (row.courseDeleted !== 0 || row.lessonDeleted !== 0) throw new Error(`课程或课时已删除：${payload.courseId}/${payload.lessonId}`)
  }
  return {
    courses: new Set(payloads.map(item => item.courseId)).size,
    documents: payloads.length,
    scenes: payloads.reduce((sum, item) => sum + item.scenes.length, 0),
  }
}

function argument(name) {
  const index = process.argv.indexOf(name)
  if (index < 0 || !process.argv[index + 1]) throw new Error(`缺少参数 ${name}`)
  return resolve(process.argv[index + 1])
}

function main() {
  const catalog = readJson(argument('--catalog'), 'catalog')
  const mapping = readJson(argument('--mapping'), 'mapping')
  const databaseRows = readJson(argument('--database-snapshot'), '数据库快照')
  const result = auditMapping(catalog, mapping, databaseRows)
  process.stdout.write(`STAGE_A_MAPPING=OK courses=${result.courses} documents=${result.documents} scenes=${result.scenes}\n`)
}

if (process.argv[1]?.endsWith('audit-stage-a-mapping.mjs')) {
  try { main() } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  }
}
