import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const catalog = JSON.parse(readFileSync(join(scriptDirectory, 'catalog.json'), 'utf8'))

function cleanupFixture(directory) {
  const target = resolve(directory)
  assert.ok(target.startsWith(resolve(tmpdir()) + sep))
  assert.ok(basename(target).startsWith('learnhub-course-seed-'))
  rmSync(target, { recursive: true, force: true })
}

function createManifestFixture(directory, { omitLastVideo = false } = {}) {
  const courses = catalog.map((course, courseIndex) => {
    const courseDirectory = join(directory, 'courses', course.slug)
    mkdirSync(courseDirectory, { recursive: true })
    writeFileSync(join(courseDirectory, 'cover.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>', 'utf8')
    const lessons = course.lessons.map((_, lessonIndex) => {
      const filename = `lesson-0${lessonIndex + 1}.mp4`
      const isLastVideo = courseIndex === catalog.length - 1 && lessonIndex === course.lessons.length - 1
      if (!omitLastVideo || !isLastVideo) {
        writeFileSync(join(courseDirectory, filename), `video-${courseIndex}-${lessonIndex}`, 'utf8')
      }
      return {
        url: `http://localhost:8091/courses/${course.slug}/${filename}`,
        durationSeconds: 41 + courseIndex * 2 + lessonIndex,
      }
    })
    return {
      slug: course.slug,
      coverUrl: `http://localhost:8091/courses/${course.slug}/cover.svg`,
      lessons,
    }
  })
  return { courses }
}

test('课程目录包含 21 门唯一课程和 42 个完整课时', () => {
  assert.equal(Array.isArray(catalog), true)
  assert.equal(catalog.length, 21)
  assert.equal(new Set(catalog.map((course) => course.slug)).size, 21)
  assert.equal(new Set(catalog.map((course) => course.title)).size, 21)
  assert.equal(catalog.flatMap((course) => course.lessons).length, 42)

  for (const course of catalog) {
    assert.match(course.slug, /^[a-z0-9]+(?:-[a-z0-9]+)*$/)
    assert.ok([1, 2, 3].includes(course.categoryId))
    assert.ok(course.title.length > 0)
    assert.ok(course.subtitle.length > 0)
    assert.ok(course.description.length > 0)
    assert.ok(course.instructor.length > 0)
    assert.match(course.accentHex, /^#[0-9A-Fa-f]{6}$/)
    assert.ok(Array.isArray(course.tags) && course.tags.length > 0)
    assert.equal(new Set(course.tags).size, course.tags.length)
    assert.equal(course.lessons.length, 2)
    for (const lesson of course.lessons) {
      assert.ok(lesson.chapterTitle.length > 0)
      assert.ok(lesson.title.length > 0)
      assert.ok(Array.isArray(lesson.scenes) && lesson.scenes.length > 0)
      assert.ok(lesson.scenes.every((scene) => typeof scene.narration === 'string' && scene.narration.trim()))
    }
  }
})

test('SQL 使用清单中的真实 URL 和时长，并提供冲突保护与幂等写入', () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'learnhub-course-seed-'))
  try {
    const manifestPath = join(temporaryDirectory, 'manifest.json')
    const outputPath = join(temporaryDirectory, 'demo-courses.sql')
    const manifest = createManifestFixture(temporaryDirectory)
    writeFileSync(manifestPath, JSON.stringify(manifest), 'utf8')
    execFileSync(process.execPath, [
      join(scriptDirectory, 'seed.mjs'),
      '--manifest', manifestPath,
      '--output', outputPath,
    ])
    const sql = readFileSync(outputPath, 'utf8')

    for (const [courseIndex, course] of manifest.courses.entries()) {
      assert.ok(sql.includes(course.coverUrl))
      for (const [lessonIndex, lesson] of course.lessons.entries()) {
        const lessonId = 32011 + courseIndex * 10 + lessonIndex
        const chapterId = 22011 + courseIndex * 10 + lessonIndex
        const lessonTitle = catalog[courseIndex].lessons[lessonIndex].title.replaceAll("'", "''")
        assert.ok(sql.includes(
          `(${lessonId}, ${chapterId}, '${lessonTitle}', '${lesson.url}', ${lesson.durationSeconds}, 1, 1)`,
        ))
      }
    }
    assert.match(sql, /SIGNAL SQLSTATE '45000'/)
    assert.match(sql, /DECLARE EXIT HANDLER FOR SQLEXCEPTION/)
    assert.match(sql, /START TRANSACTION;/)
    assert.match(sql, /COMMIT;/)
    assert.match(sql, /INSERT IGNORE INTO course /)
    assert.match(sql, /INSERT IGNORE INTO course_chapter /)
    assert.match(sql, /INSERT IGNORE INTO course_lesson /)
    assert.match(sql, /INSERT IGNORE INTO course_tag /)
    assert.match(sql, /SELECT 12001, id FROM tag WHERE name = /)
    assert.match(sql, /ON DUPLICATE KEY UPDATE\s+generation = generation \+ 1/)
    assert.doesNotMatch(sql, /\bDELETE\b/i)
    assert.ok(sql.includes('(12001,'))
    assert.ok(sql.includes('(12021,'))
    assert.ok(sql.includes('(22011,'))
    assert.ok(sql.includes('(22212,'))
    assert.ok(sql.includes('(32011,'))
    assert.ok(sql.includes('(32212,'))
  } finally {
    cleanupFixture(temporaryDirectory)
  }
})

test('媒体文件缺失时拒绝生成 SQL', () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'learnhub-course-seed-invalid-'))
  try {
    const manifestPath = join(temporaryDirectory, 'manifest.json')
    const outputPath = join(temporaryDirectory, 'demo-courses.sql')
    writeFileSync(manifestPath, JSON.stringify(createManifestFixture(temporaryDirectory, {
      omitLastVideo: true,
    })), 'utf8')
    assert.throws(() => execFileSync(process.execPath, [
      join(scriptDirectory, 'seed.mjs'),
      '--manifest', manifestPath,
      '--output', outputPath,
    ], { stdio: 'pipe' }))
  } finally {
    cleanupFixture(temporaryDirectory)
  }
})

test('媒体 URL 不指向约定的本地静态服务时拒绝生成 SQL', () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'learnhub-course-seed-url-'))
  try {
    const manifestPath = join(temporaryDirectory, 'manifest.json')
    const outputPath = join(temporaryDirectory, 'demo-courses.sql')
    const manifest = createManifestFixture(temporaryDirectory)
    manifest.courses[0].lessons[0].url = 'http://localhost:8091/courses/wrong/lesson-01.mp4'
    writeFileSync(manifestPath, JSON.stringify(manifest), 'utf8')
    assert.throws(() => execFileSync(process.execPath, [
      join(scriptDirectory, 'seed.mjs'),
      '--manifest', manifestPath,
      '--output', outputPath,
    ], { stdio: 'pipe' }))
  } finally {
    cleanupFixture(temporaryDirectory)
  }
})

