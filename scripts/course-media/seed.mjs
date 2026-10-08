#!/usr/bin/env node

import { readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const COURSE_COUNT = 21
const LESSONS_PER_COURSE = 2
const COURSE_ID_BASE = 12001
const CHAPTER_ID_BASE = 22011
const LESSON_ID_BASE = 32011
const TAG_ID_BASE = 51000

const scriptDirectory = dirname(fileURLToPath(import.meta.url))

function usage() {
  return '用法: node scripts/course-media/seed.mjs --manifest <manifest.json> --output <demo-courses.sql>'
}

function parseArguments(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index]
    const value = argv[index + 1]
    if (!key?.startsWith('--') || !value) {
      throw new Error(usage())
    }
    values.set(key.slice(2), value)
  }
  if (!values.has('manifest') || !values.has('output') || values.size !== 2) {
    throw new Error(usage())
  }
  return {
    manifestPath: resolve(values.get('manifest')),
    outputPath: resolve(values.get('output')),
  }
}

function requireString(value, field) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${field} 必须是非空字符串`)
  }
  return value.trim()
}

function requireInteger(value, field, minimum = 1) {
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`${field} 必须是大于等于 ${minimum} 的整数`)
  }
  return value
}

function sqlString(value) {
  return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''")}'`
}

function sqlSignal(message) {
  return `SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = ${sqlString(message)};`
}

function validateCatalog(rawCatalog) {
  if (!Array.isArray(rawCatalog) || rawCatalog.length !== COURSE_COUNT) {
    throw new Error(`catalog.json 必须是包含 ${COURSE_COUNT} 门课程的数组`)
  }
  const slugs = new Set()
  const titles = new Set()
  const lessonCount = rawCatalog.reduce((count, course, courseIndex) => {
    const prefix = `catalog[${courseIndex}]`
    course.slug = requireString(course.slug, `${prefix}.slug`)
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(course.slug)) {
      throw new Error(`${prefix}.slug 只能包含小写字母、数字和连字符`)
    }
    course.title = requireString(course.title, `${prefix}.title`)
    course.subtitle = requireString(course.subtitle, `${prefix}.subtitle`)
    course.description = requireString(course.description, `${prefix}.description`)
    course.instructor = requireString(course.instructor, `${prefix}.instructor`)
    course.accentHex = requireString(course.accentHex, `${prefix}.accentHex`)
    if (!/^#[0-9A-Fa-f]{6}$/.test(course.accentHex)) {
      throw new Error(`${prefix}.accentHex 必须是六位十六进制颜色`)
    }
    requireInteger(course.categoryId, `${prefix}.categoryId`)
    if (![1, 2, 3].includes(course.categoryId)) {
      throw new Error(`${prefix}.categoryId 只能是现有分类 1、2 或 3`)
    }
    if (!Array.isArray(course.tags) || course.tags.length === 0) {
      throw new Error(`${prefix}.tags 必须是非空数组`)
    }
    course.tags = course.tags.map((tag, tagIndex) => requireString(tag, `${prefix}.tags[${tagIndex}]`))
    if (new Set(course.tags).size !== course.tags.length) {
      throw new Error(`${prefix}.tags 不能包含重复标签`)
    }
    if (!Array.isArray(course.lessons) || course.lessons.length !== LESSONS_PER_COURSE) {
      throw new Error(`${prefix}.lessons 必须包含 ${LESSONS_PER_COURSE} 个课时`)
    }
    course.lessons.forEach((lesson, lessonIndex) => {
      const lessonPrefix = `${prefix}.lessons[${lessonIndex}]`
      lesson.chapterTitle = requireString(lesson.chapterTitle, `${lessonPrefix}.chapterTitle`)
      lesson.title = requireString(lesson.title, `${lessonPrefix}.title`)
      if (!Array.isArray(lesson.scenes) || lesson.scenes.length === 0) {
        throw new Error(`${lessonPrefix}.scenes 必须是非空数组`)
      }
      lesson.scenes.forEach((scene, sceneIndex) => {
        requireString(scene.narration, `${lessonPrefix}.scenes[${sceneIndex}].narration`)
      })
    })
    if (slugs.has(course.slug)) throw new Error(`课程 slug 重复: ${course.slug}`)
    if (titles.has(course.title)) throw new Error(`课程标题重复: ${course.title}`)
    slugs.add(course.slug)
    titles.add(course.title)
    return count + course.lessons.length
  }, 0)
  if (lessonCount !== COURSE_COUNT * LESSONS_PER_COURSE) {
    throw new Error(`catalog.json 必须包含 ${COURSE_COUNT * LESSONS_PER_COURSE} 个课时`)
  }
  return rawCatalog
}

async function requireNonEmptyFile(path, field) {
  let file
  try {
    file = await stat(path)
  } catch {
    throw new Error(`${field} 对应文件不存在: ${path}`)
  }
  if (!file.isFile() || file.size <= 0) {
    throw new Error(`${field} 对应文件必须是非空普通文件: ${path}`)
  }
}

async function validateManifest(rawManifest, catalog, manifestDirectory) {
  if (!rawManifest || !Array.isArray(rawManifest.courses)) {
    throw new Error('manifest.json 必须包含 courses 数组')
  }
  if (rawManifest.courses.length !== catalog.length) {
    throw new Error(`manifest.json 必须包含 ${catalog.length} 门课程的媒体信息`)
  }
  const bySlug = new Map()
  rawManifest.courses.forEach((course, courseIndex) => {
    const prefix = `manifest.courses[${courseIndex}]`
    const slug = requireString(course.slug, `${prefix}.slug`)
    if (bySlug.has(slug)) throw new Error(`manifest.json 课程 slug 重复: ${slug}`)
    const coverUrl = requireString(course.coverUrl, `${prefix}.coverUrl`)
    const expectedCoverUrl = `http://localhost:8091/courses/${slug}/cover.svg`
    if (coverUrl !== expectedCoverUrl) {
      throw new Error(`${prefix}.coverUrl 必须是 ${expectedCoverUrl}`)
    }
    if (!Array.isArray(course.lessons) || course.lessons.length !== LESSONS_PER_COURSE) {
      throw new Error(`${prefix}.lessons 必须包含 ${LESSONS_PER_COURSE} 个媒体文件`)
    }
    const lessons = course.lessons.map((lesson, lessonIndex) => {
      const url = requireString(lesson.url, `${prefix}.lessons[${lessonIndex}].url`)
      const expectedUrl = `http://localhost:8091/courses/${slug}/lesson-0${lessonIndex + 1}.mp4`
      if (url !== expectedUrl) {
        throw new Error(`${prefix}.lessons[${lessonIndex}].url 必须是 ${expectedUrl}`)
      }
      return {
        url,
        durationSeconds: requireInteger(
          lesson.durationSeconds,
          `${prefix}.lessons[${lessonIndex}].durationSeconds`,
        ),
      }
    })
    bySlug.set(slug, { slug, coverUrl, lessons })
  })
  catalog.forEach((course) => {
    if (!bySlug.has(course.slug)) {
      throw new Error(`manifest.json 缺少课程媒体: ${course.slug}`)
    }
  })
  for (const slug of bySlug.keys()) {
    if (!catalog.some((course) => course.slug === slug)) {
      throw new Error(`manifest.json 包含 catalog.json 中不存在的课程: ${slug}`)
    }
  }
  await Promise.all(catalog.flatMap((course) => [
    requireNonEmptyFile(
      resolve(manifestDirectory, 'courses', course.slug, 'cover.svg'),
      `${course.slug}.coverUrl`,
    ),
    ...course.lessons.map((_, lessonIndex) => requireNonEmptyFile(
      resolve(manifestDirectory, 'courses', course.slug, `lesson-0${lessonIndex + 1}.mp4`),
      `${course.slug}.lessons[${lessonIndex}].url`,
    )),
  ]))
  return bySlug
}

function buildConflictChecks(catalog, mediaBySlug, sortedTags) {
  const checks = []
  catalog.forEach((course, courseIndex) => {
    const courseId = COURSE_ID_BASE + courseIndex
    const media = mediaBySlug.get(course.slug)
    checks.push(
      `IF EXISTS (SELECT 1 FROM course WHERE id = ${courseId} AND title <> ${sqlString(course.title)}) THEN`,
      `    ${sqlSignal(`演示课程 ID ${courseId} 已被其他课程占用`)}`,
      'END IF;',
    )
    course.lessons.forEach((lesson, lessonIndex) => {
      const chapterId = CHAPTER_ID_BASE + courseIndex * 10 + lessonIndex
      const lessonId = LESSON_ID_BASE + courseIndex * 10 + lessonIndex
      const lessonMedia = media.lessons[lessonIndex]
      checks.push(
        `IF EXISTS (SELECT 1 FROM course_chapter WHERE id = ${chapterId} AND (course_id <> ${courseId} OR title <> ${sqlString(lesson.chapterTitle)})) THEN`,
        `    ${sqlSignal(`演示章节 ID ${chapterId} 已被其他数据占用`)}`,
        'END IF;',
        `IF EXISTS (SELECT 1 FROM course_lesson WHERE id = ${lessonId} AND (chapter_id <> ${chapterId} OR title <> ${sqlString(lesson.title)} OR COALESCE(media_url, '') <> ${sqlString(lessonMedia.url)} OR duration_seconds <> ${lessonMedia.durationSeconds} OR free_preview <> 1 OR sort_order <> 1)) THEN`,
        `    ${sqlSignal(`演示课时 ID ${lessonId} 已存在不匹配数据`)}`,
        'END IF;',
      )
    })
  })
  sortedTags.forEach((tagName, tagIndex) => {
    const tagId = TAG_ID_BASE + tagIndex
    checks.push(
      `IF EXISTS (SELECT 1 FROM tag WHERE id = ${tagId} AND name <> ${sqlString(tagName)}) AND NOT EXISTS (SELECT 1 FROM tag WHERE name = ${sqlString(tagName)}) THEN`,
      `    ${sqlSignal(`演示标签 ID ${tagId} 已被其他标签占用`)}`,
      'END IF;',
    )
  })
  return checks.map((line) => `    ${line}`).join('\n')
}

function valueRows(rows) {
  return rows.map((row) => `        (${row.join(', ')})`).join(',\n')
}

function buildSql(catalog, mediaBySlug) {
  const sortedTags = [...new Set(catalog.flatMap((course) => course.tags))]
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))

  const courseRows = []
  const chapterRows = []
  const lessonRows = []
  const tagRows = sortedTags.map((tag, index) => [TAG_ID_BASE + index, sqlString(tag)])
  const courseTags = []

  catalog.forEach((course, courseIndex) => {
    const courseId = COURSE_ID_BASE + courseIndex
    const media = mediaBySlug.get(course.slug)
    courseRows.push([
      courseId,
      course.categoryId,
      sqlString(course.title),
      sqlString(course.subtitle),
      sqlString(media.coverUrl),
      sqlString(course.description),
      sqlString(course.instructor),
      '0.00',
      sqlString('PUBLISHED'),
      'NOW(3)',
    ])
    course.lessons.forEach((lesson, lessonIndex) => {
      const chapterId = CHAPTER_ID_BASE + courseIndex * 10 + lessonIndex
      const lessonId = LESSON_ID_BASE + courseIndex * 10 + lessonIndex
      const lessonMedia = media.lessons[lessonIndex]
      chapterRows.push([chapterId, courseId, sqlString(lesson.chapterTitle), lessonIndex + 1])
      lessonRows.push([
        lessonId,
        chapterId,
        sqlString(lesson.title),
        sqlString(lessonMedia.url),
        lessonMedia.durationSeconds,
        1,
        1,
      ])
    })
    course.tags.forEach((tag) => courseTags.push({ courseId, tag }))
  })

  const courseIds = catalog.map((_, index) => COURSE_ID_BASE + index).join(', ')
  return `-- 此文件由 scripts/course-media/seed.mjs 生成，请勿手工修改。
-- 内容为本地演示短课；MySQL 是事实源，媒体由 localhost:8091 提供。
SET NAMES utf8mb4;
USE learnhub;

DELIMITER $$
DROP PROCEDURE IF EXISTS learnhub_seed_demo_courses_20261008$$
CREATE PROCEDURE learnhub_seed_demo_courses_20261008()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

${buildConflictChecks(catalog, mediaBySlug, sortedTags)}

    INSERT IGNORE INTO course (
        id, category_id, title, subtitle, cover_url, description, instructor,
        price, status, published_at
    ) VALUES
${valueRows(courseRows)};

    INSERT IGNORE INTO course_chapter (id, course_id, title, sort_order) VALUES
${valueRows(chapterRows)};

    INSERT IGNORE INTO course_lesson (
        id, chapter_id, title, media_url, duration_seconds, free_preview, sort_order
    ) VALUES
${valueRows(lessonRows)};

    INSERT IGNORE INTO tag (id, name) VALUES
${valueRows(tagRows)};

    INSERT IGNORE INTO course_tag (course_id, tag_id)
${courseTags.map(({ courseId, tag }, index) =>
    `${index === 0 ? '    ' : '    UNION ALL '}SELECT ${courseId}, id FROM tag WHERE name = ${sqlString(tag)}`
  ).join('\n')};

    INSERT INTO search_index_task (
        course_id, generation, attempts, next_retry_at, last_error
    )
    SELECT id, 1, 0, NOW(3), NULL
    FROM course
    WHERE id IN (${courseIds})
    ON DUPLICATE KEY UPDATE
        generation = generation + 1,
        attempts = 0,
        next_retry_at = NOW(3),
        last_error = NULL;

    COMMIT;
END$$
DELIMITER ;

CALL learnhub_seed_demo_courses_20261008();
DROP PROCEDURE IF EXISTS learnhub_seed_demo_courses_20261008;
`
}

async function main() {
  const { manifestPath, outputPath } = parseArguments(process.argv.slice(2))
  const catalogPath = resolve(scriptDirectory, 'catalog.json')
  const catalog = validateCatalog(JSON.parse(await readFile(catalogPath, 'utf8')))
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  const mediaBySlug = await validateManifest(manifest, catalog, dirname(manifestPath))
  const sql = buildSql(catalog, mediaBySlug)
  await writeFile(outputPath, sql, 'utf8')
  console.log(`已生成 ${catalog.length} 门课程、${catalog.length * LESSONS_PER_COURSE} 个课时的 SQL：${outputPath}`)
}

main().catch((error) => {
  console.error(`生成演示课程 SQL 失败：${error.message}`)
  process.exitCode = 1
})

