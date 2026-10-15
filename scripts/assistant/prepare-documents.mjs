import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const defaultCatalogPath = resolve(scriptDirectory, '..', 'course-media', 'catalog.json')
const maxLong = 9223372036854775807n

function fail(message) {
  throw new Error(message)
}

function readJson(path, label) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch (error) {
    fail(`无法读取${label}：${error instanceof Error ? error.message : String(error)}`)
  }
}

function requiredString(value, field, index) {
  if (typeof value !== 'string' || value.trim() === '') {
    fail(`mappings[${index}].${field} 必须是非空字符串`)
  }
  return value.trim()
}

function positiveLongString(value, field, index) {
  const normalized = requiredString(value, field, index)
  if (!/^[1-9]\d*$/.test(normalized) || normalized.length > 19 || BigInt(normalized) > maxLong) {
    fail(`mappings[${index}].${field} 必须是正 long ID 字符串`)
  }
  return normalized
}

function validateMappings(mappingFile) {
  if (mappingFile === null || typeof mappingFile !== 'object' || Array.isArray(mappingFile)) {
    fail('mapping 文件必须是 JSON 对象')
  }
  if (mappingFile.reviewed !== true) {
    fail('mapping 文件必须经人工核验并设置 reviewed: true')
  }
  if (!Array.isArray(mappingFile.mappings) || mappingFile.mappings.length === 0) {
    fail('mapping 文件必须包含非空 mappings 数组')
  }

  const seenSources = new Set()
  const seenLessons = new Set()
  const lessonOwners = new Map()
  const courseIds = new Map()
  return mappingFile.mappings.map((mapping, index) => {
    if (mapping === null || typeof mapping !== 'object' || Array.isArray(mapping)) {
      fail(`mappings[${index}] 必须是对象`)
    }
    const courseSlug = requiredString(mapping.courseSlug, 'courseSlug', index)
    const lessonTitle = requiredString(mapping.lessonTitle, 'lessonTitle', index)
    const courseId = positiveLongString(mapping.courseId, 'courseId', index)
    const lessonId = positiveLongString(mapping.lessonId, 'lessonId', index)
    const sourceKey = requiredString(mapping.sourceKey, 'sourceKey', index)
    if (sourceKey.length > 191) fail(`mappings[${index}].sourceKey 不能超过191个字符`)

    const catalogKey = `${courseSlug}\u0000${lessonTitle}`
    if (seenSources.has(sourceKey)) fail(`sourceKey 重复：${sourceKey}`)
    if (seenLessons.has(catalogKey)) fail(`课程课时映射重复：${courseSlug} / ${lessonTitle}`)
    if (lessonOwners.has(lessonId) && lessonOwners.get(lessonId) !== catalogKey) {
      fail(`lessonId 重复绑定到不同课时：${lessonId}`)
    }
    if (courseIds.has(courseSlug) && courseIds.get(courseSlug) !== courseId) {
      fail(`同一 courseSlug 使用了不同 courseId：${courseSlug}`)
    }
    seenSources.add(sourceKey)
    seenLessons.add(catalogKey)
    lessonOwners.set(lessonId, catalogKey)
    courseIds.set(courseSlug, courseId)
    return { courseSlug, lessonTitle, courseId, lessonId, sourceKey }
  })
}

function locateLesson(catalog, mapping, index) {
  const matches = []
  for (const course of catalog) {
    if (course?.slug !== mapping.courseSlug || !Array.isArray(course.lessons)) continue
    for (const lesson of course.lessons) {
      if (lesson?.title === mapping.lessonTitle) matches.push({ course, lesson })
    }
  }
  if (matches.length === 0) {
    fail(`mappings[${index}] 未匹配 catalog 课时：${mapping.courseSlug} / ${mapping.lessonTitle}`)
  }
  if (matches.length > 1) {
    fail(`mappings[${index}] 在 catalog 中匹配到重复课时：${mapping.courseSlug} / ${mapping.lessonTitle}`)
  }
  return matches[0]
}

function scenesFor(lesson, index) {
  if (!Array.isArray(lesson.scenes) || lesson.scenes.length === 0) {
    fail(`mappings[${index}] 对应课时没有 scenes`)
  }
  return lesson.scenes.map((scene, sceneIndex) => {
    if (scene === null || typeof scene !== 'object' || Array.isArray(scene)) {
      fail(`mappings[${index}] 的 scenes[${sceneIndex}] 必须是对象`)
    }
    const heading = typeof scene.heading === 'string' ? scene.heading.trim() : ''
    const narration = typeof scene.narration === 'string' ? scene.narration.trim() : ''
    const body = typeof scene.body === 'string' ? scene.body.trim() : ''
    const content = narration || body
    if (!content) fail(`mappings[${index}] 的 scenes[${sceneIndex}] 缺少 narration/body 正文`)
    return { heading, content }
  })
}

export function prepareDocuments(catalog, mappingFile) {
  if (!Array.isArray(catalog)) fail('catalog 必须是课程数组')
  const mappings = validateMappings(mappingFile)
  return mappings.map((mapping, index) => {
    const { lesson } = locateLesson(catalog, mapping, index)
    return {
      sourceKey: mapping.sourceKey,
      title: mapping.lessonTitle,
      courseId: mapping.courseId,
      lessonId: mapping.lessonId,
      scenes: scenesFor(lesson, index),
    }
  })
}

function parseArguments(argv) {
  let catalogPath = defaultCatalogPath
  let mappingPath
  const seen = new Set()
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index]
    const value = argv[index + 1]
    if (!['--catalog', '--mapping'].includes(option) || value === undefined || seen.has(option)) {
      fail('用法：node scripts/assistant/prepare-documents.mjs --mapping <path> [--catalog <path>]')
    }
    seen.add(option)
    if (option === '--catalog') catalogPath = resolve(value)
    if (option === '--mapping') mappingPath = resolve(value)
  }
  if (!mappingPath) fail('必须通过 --mapping 提供经人工核验的映射文件')
  return { catalogPath, mappingPath }
}

function main() {
  const { catalogPath, mappingPath } = parseArguments(process.argv.slice(2))
  const payloads = prepareDocuments(readJson(catalogPath, 'catalog'), readJson(mappingPath, 'mapping 文件'))
  process.stdout.write(`${JSON.stringify(payloads, null, 2)}\n`)
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    main()
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  }
}
